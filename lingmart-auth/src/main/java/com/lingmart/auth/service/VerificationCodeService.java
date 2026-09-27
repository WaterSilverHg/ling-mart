package com.lingmart.auth.service;

import com.lingmart.common.util.RedisRateLimiter;
import com.lingmart.common.exception.BizException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;

/**
 * 短信验证码服务。
 *
 * <p>发送时生成随机 6 位数字验证码，按手机号写入 Redis（5 分钟有效、一次性使用），
 * 多实例部署共享。是否返回明文由控制器按 {@code lingmart.auth.dev-code-enabled} 开关控制。
 * 防刷：同一手机号 60 秒内限 1 条、每小时限 10 条（Redis 分布式限流）。</p>
 */
@Service
public class VerificationCodeService {

    /** Redis key 前缀 */
    private static final String KEY_PREFIX = "lingmart:auth:code:";

    /** 验证码有效期（毫秒） */
    private static final long CODE_EXPIRE_MS = 5 * 60_000L;

    /** 防刷：同一手机号发送间隔（毫秒）与每小时上限 */
    private static final long SEND_INTERVAL_MS = 60_000;
    private static final int HOURLY_LIMIT = 10;

    private final StringRedisTemplate redisTemplate;
    private final RedisRateLimiter rateLimiter;
    private final SecureRandom random = new SecureRandom();

    public VerificationCodeService(StringRedisTemplate redisTemplate, RedisRateLimiter rateLimiter) {
        this.redisTemplate = redisTemplate;
        this.rateLimiter = rateLimiter;
    }

    /**
     * 发送验证码：限流通过后生成随机 6 位数字码并写入 Redis。
     *
     * <p>返回明文仅供开发模式回填；生产环境下发短信后应返回 null。
     * 控制器是否将明文暴露给调用方，由 {@code lingmart.auth.dev-code-enabled} 决定。</p>
     *
     * @param phone 手机号
     * @return 验证码（供开发模式回填）
     */
    public String send(String phone) {
        if (!rateLimiter.allow("code-send:" + phone, 1, SEND_INTERVAL_MS)) {
            throw new BizException("验证码发送过于频繁，请稍后再试");
        }
        if (!rateLimiter.allow("code-hour:" + phone, HOURLY_LIMIT, 3600_000L)) {
            throw new BizException("验证码发送次数已达上限，请稍后再试");
        }
        String code = String.format("%06d", random.nextInt(1_000_000));
        redisTemplate.opsForValue().set(KEY_PREFIX + phone, code, Duration.ofMillis(CODE_EXPIRE_MS));
        // TODO: 接入阿里云短信后，改为调用第三方服务下发短信，返回 null
        return code;
    }

    /**
     * 校验验证码：仅匹配该手机号最近一次下发且未过期的验证码，校验通过后作废（一次性）。
     *
     * @param phone 手机号
     * @param code  用户输入的验证码
     * @return true 表示校验通过
     */
    public boolean verify(String phone, String code) {
        if (code == null) {
            return false;
        }
        String redisKey = KEY_PREFIX + phone;
        String stored = redisTemplate.opsForValue().get(redisKey);
        if (stored == null) {
            return false;
        }
        if (!stored.equals(code)) {
            return false;
        }
        redisTemplate.delete(redisKey); // 一次性使用
        return true;
    }
}
