package com.lingmart.common.jwt;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.UUID;

/**
 * JWT 工具类（基于 JJWT 0.12.x 新链式 API）。
 *
 * <p>负责 Token 的签发与解析校验。采用对称加密 HS256，auth 服务签发、
 * gateway 校验，两侧共享同一把密钥（配置于各自的 application.yml）。</p>
 *
 * <p>注意：本类为静态工具，不含 Spring 依赖，任何服务都可直接使用。</p>
 */
public final class JwtUtils {

    /** JWT 载荷中的角色声明键 */
    public static final String CLAIM_ROLE = "role";

    private JwtUtils() {
    }

    /**
     * 签发 JWT Token。
     *
     * @param userId       用户 ID（作为 subject）
     * @param role         用户角色（如 ROLE_USER / ROLE_SELLER / ROLE_ADMIN）
     * @param secret       签名密钥（HS256 要求至少 32 字节）
     * @param expireMillis 有效期（毫秒）
     * @return 签发的 JWT 字符串
     */
    public static String generateToken(Long userId, String role, String secret, long expireMillis) {
        Date now = new Date();
        SecretKey key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        return Jwts.builder()
                .id(UUID.randomUUID().toString()) // jti：令牌唯一标识，供服务端撤销（logout 黑名单）
                .subject(String.valueOf(userId))
                .claim(CLAIM_ROLE, role)
                .issuedAt(now)
                .expiration(new Date(now.getTime() + expireMillis))
                .signWith(key)
                .compact();
    }

    /**
     * 解析并校验 Token，返回载荷。
     *
     * @param token   JWT 字符串
     * @param secret  签名密钥
     * @return 解析成功的载荷（含 subject、role、过期时间等）
     * @throws io.jsonwebtoken.JwtException Token 非法、被篡改或已过期时抛出
     */
    public static Claims parseToken(String token, String secret) {
        SecretKey key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    /**
     * 校验 Token 是否合法且未过期。
     *
     * @param token  JWT 字符串
     * @param secret 签名密钥
     * @return true 表示合法，false 表示非法/过期
     */
    public static boolean validate(String token, String secret) {
        try {
            parseToken(token, secret);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
