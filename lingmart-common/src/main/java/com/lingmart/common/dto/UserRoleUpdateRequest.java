package com.lingmart.common.dto;

import lombok.Data;

/**
 * 用户角色更新请求（跨服务契约 DTO）。
 *
 * <p>由 lingmart-shop 通过内部接口调用 lingmart-auth，将审核通过的用户
 * 角色提升为 {@code ROLE_SELLER}。新角色在用户下次登录时签发的 JWT 中生效。</p>
 */
@Data
public class UserRoleUpdateRequest {

    /** 用户 ID */
    private Long userId;

    /** 目标角色（如 ROLE_SELLER） */
    private String role;
}
