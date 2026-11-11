package com.lingmart.auth.controller;

import com.lingmart.common.constant.AuthConstants;
import com.lingmart.common.dto.UserInfo;
import com.lingmart.common.dto.UserRoleUpdateRequest;
import com.lingmart.common.exception.BizException;
import com.lingmart.common.result.R;
import com.lingmart.common.result.ResultCode;
import com.lingmart.auth.service.UserService;
import com.lingmart.auth.vo.UserVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
 * <p>路径前缀 {@code /internal}，网关只路由 {@code /api/**}，故请求不会经网关进入本类。</p>
 *
 * <p><b>但这不等于「不对外暴露」</b>：它只说明网关不转发，服务端口本身若发布到宿主机，
 * 任何人都能绕过网关直达这里。因此本类**不依赖网络边界做鉴权**——
 * 敏感操作（如角色写入）必须自带业务约束，见 {@link #updateRole}。
 * 部署侧的隔离由 {@code docker-compose.prod.yml}（业务端口不发布）与
 * {@code docker-compose.business.yml}（仅绑定 127.0.0.1）保证。</p>
 */
@Slf4j
@RestController
@RequestMapping("/internal/user")
@RequiredArgsConstructor
public class AuthInternalController {

    private final UserService userService;

    /**
     * 更新用户角色（入驻审核通过后提升为卖家）。
     *
     * <p><b>为什么单独禁掉管理员角色</b>：本类接口没有任何鉴权（见类注释中「不对外暴露」的约定），
     * 目前唯一的调用方是 shop 的入驻审核，只会写 {@code ROLE_SELLER}。
     * 若允许这里写入 {@code ROLE_ADMIN}，那么任何能直达服务端口的人都有一条现成的提权路径；
     * 管理员角色的调整只应走管理端接口（{@code AdminUserController}，需 ROLE_ADMIN + 网关白名单外）。</p>
     *
     * @param request 用户 ID 与目标角色
     * @return 成功提示
     */
    @PostMapping("/role")
    public R<Void> updateRole(@RequestBody UserRoleUpdateRequest request) {
        if (AuthConstants.ROLE_ADMIN.equals(request.getRole())) {
            log.warn("拒绝通过内部接口提权为管理员：userId={}", request.getUserId());
            throw new BizException(ResultCode.FORBIDDEN.getCode(), "内部接口不允许提权为管理员");
        }
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
