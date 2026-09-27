package com.lingmart.shop.feign;

import com.lingmart.common.dto.UserInfo;
import com.lingmart.common.dto.UserRoleUpdateRequest;
import com.lingmart.common.result.R;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * 认证服务 Feign 客户端。
 *
 * <p>服务名 {@code lingmart-auth} 经 Nacos 服务发现 + 负载均衡解析。
 * 调用 auth 的内部接口（不经过网关）完成用户角色更新。</p>
 */
@FeignClient(name = "lingmart-auth")
public interface AuthFeignClient {

    /**
     * 更新用户角色（入驻审核通过后提升为卖家）。
     *
     * @param request 用户 ID 与目标角色
     * @return 统一响应
     */
    @PostMapping("/internal/user/role")
    R<Void> updateRole(@RequestBody UserRoleUpdateRequest request);

    /**
     * 按 ID 查询用户公开信息（店铺主页展示店主头像/昵称，手机号由各服务自行裁剪）。
     *
     * @param userId 用户 ID
     * @return 用户信息；用户不存在时 data 为 null
     */
    @GetMapping("/internal/user/{id}")
    R<UserInfo> getUser(@PathVariable("id") Long userId);
}
