package com.lingmart.common.util;

import com.lingmart.common.exception.BizException;
import com.lingmart.common.result.ResultCode;

/**
 * 鉴权辅助工具。
 *
 * <p>校验当前请求用户（网关透传的角色）是否具备所需角色，
 * 不具备时抛出 403 业务异常。注意：角色来自 JWT，用户角色变更后需重新登录才生效。</p>
 */
public final class AuthUtils {

    private AuthUtils() {
    }

    /**
     * 校验当前角色是否具备目标角色权限。
     *
     * @param currentRole   当前角色（网关透传的 X-User-Role）
     * @param requiredRole  所需角色
     * @throws BizException 无权限时抛出 403
     */
    public static void requireRole(String currentRole, String requiredRole) {
        if (currentRole == null || !requiredRole.equals(currentRole)) {
            throw new BizException(ResultCode.FORBIDDEN.getCode(), "无权访问");
        }
    }
}
