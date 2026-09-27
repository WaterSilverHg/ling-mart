package com.lingmart.auth.service;

import com.lingmart.auth.dto.LoginDTO;
import com.lingmart.auth.dto.RegisterDTO;
import com.lingmart.auth.entity.User;
import com.lingmart.auth.mapper.UserMapper;
import com.lingmart.auth.service.impl.UserServiceImpl;
import com.lingmart.common.util.RedisRateLimiter;
import com.lingmart.auth.vo.LoginVO;
import com.lingmart.auth.vo.UserVO;
import com.lingmart.common.constant.AuthConstants;
import com.lingmart.common.exception.BizException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 用户服务单元测试（Mockito，不依赖数据库/中间件）。
 *
 * <p>覆盖注册、登录、当前用户查询的正常与异常分支。</p>
 */
@ExtendWith(MockitoExtension.class)
class UserServiceImplTest {

    private static final String SECRET = "test-secret-key-for-jwt-2026-please-change-in-production";

    @Mock
    private UserMapper userMapper;

    @Mock
    private VerificationCodeService verificationCodeService;

    @Mock
    private BCryptPasswordEncoder passwordEncoder;

    /** 登录防爆破限流器（构造注入必需；mock 下 hit 默认返回 0，不会触发锁定） */
    @Mock
    private RedisRateLimiter rateLimiter;

    /** Redis 模板（退出登录黑名单写入；构造注入必需） */
    @Mock
    private org.springframework.data.redis.core.StringRedisTemplate redisTemplate;
    @Mock
    private org.springframework.data.redis.core.ValueOperations<String, String> valueOps;

    @InjectMocks
    private UserServiceImpl userService;

    @BeforeEach
    void setUp() {
        // 注入 @Value 字段（Mockito 无法注入，需反射设置）
        ReflectionTestUtils.setField(userService, "jwtSecret", SECRET);
        ReflectionTestUtils.setField(userService, "expireMinutes", 120L);
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOps);
    }

    // ==================== 注册 ====================

    @Test
    void register_shouldRejectWrongCode() {
        when(verificationCodeService.verify(anyString(), anyString())).thenReturn(false);

        RegisterDTO dto = registerDTO("13900000001");
        assertThrows(BizException.class, () -> userService.register(dto));
        verify(userMapper, never()).insert(any(User.class));
    }

    @Test
    void register_shouldRejectDuplicatedPhone() {
        when(verificationCodeService.verify(anyString(), anyString())).thenReturn(true);
        when(userMapper.selectCount(any())).thenReturn(1L);

        RegisterDTO dto = registerDTO("13900000001");
        assertThrows(BizException.class, () -> userService.register(dto));
        verify(userMapper, never()).insert(any(User.class));
    }

    @Test
    void register_shouldInsertEncodedUser() {
        when(verificationCodeService.verify(anyString(), anyString())).thenReturn(true);
        when(userMapper.selectCount(any())).thenReturn(0L);
        when(passwordEncoder.encode("pass123456")).thenReturn("$2a$encodedHash");

        userService.register(registerDTO("13900000001"));

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userMapper).insert(captor.capture());
        User saved = captor.getValue();
        assertEquals("13900000001", saved.getPhone());
        assertEquals("$2a$encodedHash", saved.getPassword());
        assertEquals(AuthConstants.ROLE_USER, saved.getRole());
        assertEquals(1, saved.getStatus());
    }

    // ==================== 登录 ====================

    @Test
    void login_shouldRejectUnknownPhone() {
        when(userMapper.selectOne(any())).thenReturn(null);

        assertThrows(BizException.class, () -> userService.login(loginDTO("13900000001", "pass123456")));
    }

    @Test
    void login_shouldRejectWrongPassword() {
        when(userMapper.selectOne(any())).thenReturn(userOf("13900000001", "$2a$realHash"));
        when(passwordEncoder.matches("pass123456", "$2a$realHash")).thenReturn(false);

        assertThrows(BizException.class, () -> userService.login(loginDTO("13900000001", "pass123456")));
    }

    @Test
    void login_shouldRejectDisabledAccount() {
        User user = userOf("13900000001", "$2a$realHash");
        user.setStatus(0);
        when(userMapper.selectOne(any())).thenReturn(user);
        when(passwordEncoder.matches(anyString(), anyString())).thenReturn(true);

        assertThrows(BizException.class, () -> userService.login(loginDTO("13900000001", "pass123456")));
    }

    @Test
    void login_shouldReturnTokenAndUser() {
        when(userMapper.selectOne(any())).thenReturn(userOf("13900000001", "$2a$realHash"));
        when(passwordEncoder.matches("pass123456", "$2a$realHash")).thenReturn(true);

        LoginVO vo = userService.login(loginDTO("13900000001", "pass123456"));

        assertNotNull(vo.getToken());
        assertTrue(vo.getToken().split("\\.").length == 3, "Token 应为三段式 JWT");
        assertEquals("13900000001", vo.getUser().getPhone());
    }

    // ==================== 当前用户 ====================

    @Test
    void getCurrentUser_shouldRejectNullId() {
        assertThrows(BizException.class, () -> userService.getCurrentUser(null));
    }

    @Test
    void getCurrentUser_shouldRejectMissingUser() {
        when(userMapper.selectById(1L)).thenReturn(null);
        assertThrows(BizException.class, () -> userService.getCurrentUser(1L));
    }

    @Test
    void getCurrentUser_shouldReturnUserVo() {
        when(userMapper.selectById(1L)).thenReturn(userOf("13900000001", "$2a$realHash"));

        UserVO vo = userService.getCurrentUser(1L);

        assertEquals(1L, vo.getId());
        assertEquals("13900000001", vo.getPhone());
        assertEquals(AuthConstants.ROLE_USER, vo.getRole());
    }

    // ==================== 退出登录 ====================

    @Test
    void logout_shouldBlacklistValidToken() {
        String token = com.lingmart.common.jwt.JwtUtils.generateToken(1L, AuthConstants.ROLE_USER, SECRET, 120 * 60_000L);

        userService.logout("Bearer " + token);

        // jti 前缀 + 值 "1" + 剩余有效期（正数）写入 Redis
        verify(valueOps).set(startsWith("lingmart:jwt:blacklist:"), eq("1"), any(java.time.Duration.class));
    }

    @Test
    void logout_shouldIgnoreEmptyOrInvalidToken() {
        // 空头 / 无 Bearer 前缀 / 非法 token 均静默忽略，不写黑名单
        userService.logout(null);
        userService.logout("");
        userService.logout("not-a-bearer-token");
        userService.logout("Bearer invalid.token.here");
        verify(valueOps, never()).set(anyString(), anyString(), any(java.time.Duration.class));
    }

    // ==================== 测试辅助方法 ====================

    private RegisterDTO registerDTO(String phone) {
        RegisterDTO dto = new RegisterDTO();
        dto.setPhone(phone);
        dto.setCode("123456");
        dto.setPassword("pass123456");
        dto.setNickname("测试用户");
        return dto;
    }

    private LoginDTO loginDTO(String phone, String password) {
        LoginDTO dto = new LoginDTO();
        dto.setPhone(phone);
        dto.setPassword(password);
        return dto;
    }

    private User userOf(String phone, String encodedPassword) {
        User user = new User();
        user.setId(1L);
        user.setPhone(phone);
        user.setPassword(encodedPassword);
        user.setNickname("测试用户");
        user.setRole(AuthConstants.ROLE_USER);
        user.setStatus(1);
        return user;
    }
}
