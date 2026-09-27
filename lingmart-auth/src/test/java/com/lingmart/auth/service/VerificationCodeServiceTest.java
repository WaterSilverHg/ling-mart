package com.lingmart.auth.service;

import com.lingmart.common.util.RedisRateLimiter;
import com.lingmart.common.exception.BizException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * 验证码服务单元测试（Redis 版）。
 *
 * <p>验证码为随机 6 位数字（是否明文返回由控制器按开发模式开关控制）；覆盖生成格式、
 * 正确/错误校验、一次性使用、防刷限流。Redis 与限流器均以 Mockito 打桩，
 * 不依赖真实 Redis 实例。</p>
 */
@ExtendWith(MockitoExtension.class)
class VerificationCodeServiceTest {

    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private ValueOperations<String, String> valueOps;
    @Mock
    private RedisRateLimiter rateLimiter;

    private VerificationCodeService service;

    @BeforeEach
    void setUp() {
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOps);
        lenient().when(rateLimiter.allow(anyString(), anyInt(), anyLong())).thenReturn(true);
        service = new VerificationCodeService(redisTemplate, rateLimiter);
    }

    /** 发送返回 6 位数字验证码（随机生成，非固定 123456） */
    @Test
    void send_返回6位数字验证码() {
        String code = service.send("13800000000");
        assertTrue(code.matches("\\d{6}"), "验证码应为 6 位数字，实际：" + code);
    }

    /** 用发送返回的验证码可校验通过 */
    @Test
    void verify_正确验证码通过() {
        String code = service.send("13800000000");
        when(valueOps.get("lingmart:auth:code:13800000000")).thenReturn(code);
        assertTrue(service.verify("13800000000", code));
    }

    /** 错误验证码拒绝 */
    @Test
    void verify_错误验证码拒绝() {
        when(valueOps.get("lingmart:auth:code:13800000000")).thenReturn("123456");
        assertFalse(service.verify("13800000000", "000000"));
    }

    /** 验证码一次性使用：校验通过后即作废（Redis 删除后再次查询返回 null） */
    @Test
    void verify_一次性使用() {
        String code = service.send("13800000000");
        // 第一次 get 返回验证码（校验通过后 delete），第二次 get 返回 null（模拟已删除）
        when(valueOps.get("lingmart:auth:code:13800000000")).thenReturn(code, (String) null);
        assertTrue(service.verify("13800000000", code));
        assertFalse(service.verify("13800000000", code), "验证码应一次性使用");
    }

    /** 不同手机号验证码互不影响（B 手机号查不到 A 的验证码） */
    @Test
    void verify_不同手机号互不影响() {
        String code = service.send("13800000001");
        when(valueOps.get("lingmart:auth:code:13800000002")).thenReturn(null);
        assertFalse(service.verify("13800000002", code), "A 手机号的验证码不能用于 B 手机号");
    }

    /** 防刷：同一手机号 60 秒内限 1 条（Redis 限流器返回 false 时拒绝） */
    @Test
    void send_同一手机号限1条() {
        service.send("13800000000");
        when(rateLimiter.allow(eq("code-send:13800000000"), eq(1), eq(60_000L))).thenReturn(false);
        assertThrows(BizException.class, () -> service.send("13800000000"));
    }
}
