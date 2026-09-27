package com.lingmart.common.constant;

/**
 * 认证相关常量：请求头、角色、路径等。
 *
 * <p>网关解析 JWT 后，将 userId / role 写入请求头透传给下游服务；
 * 下游服务从请求头读取当前登录用户信息。请求头名称在此统一约定。</p>
 */
public final class AuthConstants {

    /** 认证请求头 */
    public static final String HEADER_AUTHORIZATION = "Authorization";

    /** JWT 前缀（携带时格式为 {@code Bearer <token>}） */
    public static final String TOKEN_PREFIX = "Bearer ";

    /** 网关透传的用户 ID 请求头 */
    public static final String HEADER_USER_ID = "X-User-Id";

    /** 网关透传的用户角色请求头 */
    public static final String HEADER_USER_ROLE = "X-User-Role";

    /** 普通用户角色 */
    public static final String ROLE_USER = "ROLE_USER";

    /** 卖家角色（入驻审核通过后赋予） */
    public static final String ROLE_SELLER = "ROLE_SELLER";

    /** 管理员角色 */
    public static final String ROLE_ADMIN = "ROLE_ADMIN";

    private AuthConstants() {
    }
}
