package com.lingmart.auth.controller;

import com.lingmart.auth.service.UserService;
import com.lingmart.auth.vo.UserVO;
import com.lingmart.common.constant.AuthConstants;
import com.lingmart.common.dto.UserRoleUpdateRequest;
import com.lingmart.common.exception.BizException;
import com.lingmart.common.result.R;
import com.lingmart.common.util.AuthUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 认证中心管理端接口（角色修正等）。
 *
 * <p>路径前缀 {@code /api/admin/auth}，经网关路由（仅 ROLE_ADMIN 可访问）；本类内
 * 再以网关透传的 {@code X-User-Role} 做二次校验（纵深防御）。用途：入驻审核通过但
 * auth 角色提升失败（旁路更新未纳入 Seata）时，由管理员手工修正用户角色。</p>
 */
@RestController
@RequestMapping("/api/admin/auth/users")
@RequiredArgsConstructor
public class AdminUserController {

    private final UserService userService;

    /**
     * 按手机号查询用户（便于定位角色提升失败的账号）。
     *
     * @param role   网关透传的角色（须 ROLE_ADMIN）
     * @param phone  手机号（精确匹配）
     * @return 用户信息；不存在返回 data=null
     */
    @GetMapping
    public R<UserVO> findByPhone(@RequestHeader(value = AuthConstants.HEADER_USER_ROLE, required = false) String role,
                                 @RequestParam String phone) {
        AuthUtils.requireRole(role, AuthConstants.ROLE_ADMIN);
        if (!StringUtils.hasText(phone)) {
            throw new BizException("手机号不能为空");
        }
        return R.ok(userService.getUserInfoByPhone(phone));
    }

    /**
     * 手工修正用户角色（如补发卖家角色）。
     *
     * @param role    网关透传的角色（须 ROLE_ADMIN）
     * @param userId  目标用户 ID
     * @param request 目标角色
     * @return 成功提示
     */
    @PostMapping("/{userId}/role")
    public R<Void> updateRole(@RequestHeader(value = AuthConstants.HEADER_USER_ROLE, required = false) String role,
                              @PathVariable Long userId,
                              @RequestBody UserRoleUpdateRequest request) {
        AuthUtils.requireRole(role, AuthConstants.ROLE_ADMIN);
        userService.updateRole(userId, request.getRole());
        return R.ok();
    }
}
