package com.lingmart.common.jwt;

import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * JWT 工具类单元测试。
 *
 * <p>覆盖：签发-解析往返、合法性校验、篡改校验、过期校验、错误密钥校验。</p>
 */
class JwtUtilsTest {

    /** 测试密钥（HS256 要求 ≥32 字节，此处取 54 字符） */
    private static final String SECRET = "test-secret-key-for-jwt-2026-please-change-in-production";
    /** 有效期：120 分钟 */
    private static final long EXPIRE = 120 * 60 * 1000L;

    @Test
    void shouldRoundTripUserAndRole() {
        String token = JwtUtils.generateToken(1L, "ROLE_USER", SECRET, EXPIRE);
        Claims claims = JwtUtils.parseToken(token, SECRET);

        assertEquals("1", claims.getSubject());
        assertEquals("ROLE_USER", claims.get(JwtUtils.CLAIM_ROLE, String.class));
        assertNotNull(claims.getExpiration());
    }

    @Test
    void shouldValidateLegalToken() {
        String token = JwtUtils.generateToken(1L, "ROLE_ADMIN", SECRET, EXPIRE);
        assertTrue(JwtUtils.validate(token, SECRET));
    }

    @Test
    void shouldRejectTamperedToken() {
        String token = JwtUtils.generateToken(1L, "ROLE_USER", SECRET, EXPIRE);
        // 篡改载荷最后两位，破坏签名
        String tampered = token.substring(0, token.length() - 2) + "xx";
        assertFalse(JwtUtils.validate(tampered, SECRET));
    }

    @Test
    void shouldRejectExpiredToken() {
        // 过期时间为负数（过去时间）
        String token = JwtUtils.generateToken(1L, "ROLE_USER", SECRET, -1000);
        assertFalse(JwtUtils.validate(token, SECRET));
    }

    @Test
    void shouldRejectWrongSecret() {
        String token = JwtUtils.generateToken(1L, "ROLE_USER", SECRET, EXPIRE);
        assertThrows(Exception.class,
                () -> JwtUtils.parseToken(token, "another-secret-key-for-jwt-2026-please-change-now"));
    }
}
