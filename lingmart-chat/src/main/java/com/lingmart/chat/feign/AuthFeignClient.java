package com.lingmart.chat.feign;

import com.lingmart.common.dto.UserInfo;
import com.lingmart.common.result.R;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.List;

/**
 * 认证服务 Feign 客户端：获取用户公开信息（昵称/头像/手机号，聊天窗口展示）。
 */
@FeignClient(name = "lingmart-auth")
public interface AuthFeignClient {

    /**
     * 按 ID 查询用户公开信息。
     *
     * @param userId 用户 ID
     * @return 用户信息；用户不存在时 data 为 null
     */
    @GetMapping("/internal/user/{id}")
    R<UserInfo> getUser(@PathVariable("id") Long userId);

    /**
     * 按 ID 集合批量查询用户公开信息（会话列表/历史消息等批量场景，避免逐个 Feign 的 N+1）。
     *
     * @param userIds 用户 ID 列表
     * @return 用户信息列表（data 为 List，可能为空；未命中的 id 不占位）
     */
    @PostMapping("/internal/user/batch")
    R<List<UserInfo>> listUsers(@RequestBody List<Long> userIds);
}
