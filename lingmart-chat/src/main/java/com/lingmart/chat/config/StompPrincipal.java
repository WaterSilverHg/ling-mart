package com.lingmart.chat.config;

import java.security.Principal;

/**
 * STOMP 连接主体：仅承载用户 ID，避免引入 Spring Security 依赖。
 *
 * <p>{@link JwtChannelInterceptor} 在 CONNECT 时校验 token 后注入；
 * 消息处理端点从 {@link Principal} 还原用户 ID 识别发送者。</p>
 */
public class StompPrincipal implements Principal {

    private final Long userId;

    public StompPrincipal(Long userId) {
        this.userId = userId;
    }

    public Long getUserId() {
        return userId;
    }

    @Override
    public String getName() {
        return String.valueOf(userId);
    }
}
