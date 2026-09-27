package com.lingmart.auth.controller;

import com.lingmart.common.dto.UserInfo;
import com.lingmart.common.dto.UserRoleUpdateRequest;
import com.lingmart.common.result.R;
import com.lingmart.auth.service.UserService;
import com.lingmart.auth.vo.UserVO;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

/**
 * 认证中心内部接口（供服务间 Feign 调用）。
 *
 * <p>路径前缀 {@code /internal}，网关只路由 {@code /api/**}，故本类接口
 * 不经过网关、不对外暴露，仅由服务间直接调用。</p>
 */
@RestController
@RequestMapping("/internal/user")
@RequiredArgsConstructor
public class AuthInternalController {

    private final UserService userService;

    /**
     * 更新用户角色（入驻审核通过后提升为卖家）。
     *
     * @param request 用户 ID 与目标角色
     * @return 成功提示
     */
    @PostMapping("/role")
    public R<Void> updateRole(@RequestBody UserRoleUpdateRequest request) {
        userService.updateRole(request.getUserId(), request.getRole());
        return R.ok();
    }

    /**
     * 按 ID 查询用户信息（聊天/店铺主页展示昵称、头像、手机号等，仅供服务间内部调用）。
     *
     * @param userId 用户 ID
     * @return 用户信息；用户不存在时 data 为 null
     */
    @GetMapping("/{id}")
    public R<UserInfo> getUser(@PathVariable("id") Long userId) {
        return R.ok(toUserInfo(userService.getUserInfo(userId)));
    }

    /**
     * 按 ID 集合批量查询用户信息（聊天会话列表等批量聚合场景，避免逐个 Feign 的 N+1）。
     *
     * @param userIds 用户 ID 列表
     * @return 用户信息列表（data 为 List，可能为空；未命中的 id 不占位）
     */
    @PostMapping("/batch")
    public R<List<UserInfo>> listUsers(@RequestBody List<Long> userIds) {
        List<UserInfo> result = new ArrayList<>();
        for (UserVO user : userService.listUserInfo(userIds)) {
            UserInfo info = toUserInfo(user);
            if (info != null) {
                result.add(info);
            }
        }
        return R.ok(result);
    }

    private UserInfo toUserInfo(UserVO user) {
        if (user == null) {
            return null;
        }
        UserInfo info = new UserInfo();
        info.setId(user.getId());
        info.setPhone(user.getPhone());
        info.setNickname(user.getNickname());
        info.setAvatar(user.getAvatar());
        info.setRole(user.getRole());
        return info;
    }
}
