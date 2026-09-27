package com.lingmart.chat.config;

import com.lingmart.chat.entity.ChatConversation;
import com.lingmart.chat.mapper.ChatConversationMapper;
import com.lingmart.common.jwt.JwtUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageDeliveryException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.stereotype.Component;

import java.security.Principal;
import java.util.Objects;

/**
 * STOMP 通道鉴权拦截器。
 *
 * <p>① CONNECT：校验 {@code token} 头（浏览器原生 WebSocket 无法设置 HTTP 头，故不走
 * Authorization），通过后把用户 ID 注入 principal（{@link StompPrincipal}）；
 * ② SUBSCRIBE：对 {@code /topic/conversation/{id}} 的订阅必须是该会话的成员
 * （买家或店主），防止任意已登录用户偷听他人会话。</p>
 *
 * <p>token 走 STOMP 头而非查询参数，避免出现在 URL 与网关访问日志中。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtChannelInterceptor implements ChannelInterceptor {

    /** JWT 签名密钥（与 auth / gateway 一致） */
    @Value("${lingmart.jwt.secret}")
    private String jwtSecret;

    private final ChatConversationMapper conversationMapper;

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null || accessor.getCommand() == null) {
            return message;
        }
        if (StompCommand.CONNECT.equals(accessor.getCommand())) {
            authenticate(accessor);
        } else if (StompCommand.SUBSCRIBE.equals(accessor.getCommand())) {
            authorizeSubscribe(accessor);
        }
        return message;
    }

    /** CONNECT 校验 token 并注入 principal */
    private void authenticate(StompHeaderAccessor accessor) {
        String token = accessor.getFirstNativeHeader("token");
        Long userId = parseUserId(token);
        if (userId == null) {
            log.warn("WebSocket 连接认证失败：token={}", token == null ? "缺失" : "非法");
            throw new MessageDeliveryException("登录已过期，请重新登录");
        }
        accessor.setUser(new StompPrincipal(userId));
        log.info("WebSocket 连接已认证：userId={}", userId);
    }

    /** SUBSCRIBE 校验目标 topic 的会话成员资格 */
    private void authorizeSubscribe(StompHeaderAccessor accessor) {
        Principal principal = accessor.getUser();
        if (principal == null) {
            throw new MessageDeliveryException("WebSocket 未认证，请重新登录");
        }
        String destination = accessor.getDestination();
        if (destination == null) {
            return;
        }
        // 仅校验会话 topic；其余（预留 /user/queue 等）放行
        if (!destination.startsWith("/topic/conversation/")) {
            return;
        }
        Long conversationId = parseConversationId(destination);
        Long userId = principal instanceof StompPrincipal p ? p.getUserId() : null;
        if (conversationId == null || userId == null || !isParticipant(userId, conversationId)) {
            log.warn("WebSocket 订阅被拒：userId={}, destination={}", userId, destination);
            throw new MessageDeliveryException("无权订阅该会话");
        }
    }

    /** 判断用户是否为会话成员（买家或店主） */
    private boolean isParticipant(Long userId, Long conversationId) {
        ChatConversation conv = conversationMapper.selectById(conversationId);
        if (conv == null) {
            return false;
        }
        return Objects.equals(conv.getBuyerId(), userId) || Objects.equals(conv.getShopUserId(), userId);
    }

    /** 从 /topic/conversation/{id} 解析会话 ID */
    private Long parseConversationId(String destination) {
        try {
            return Long.valueOf(destination.substring("/topic/conversation/".length()));
        } catch (Exception e) {
            return null;
        }
    }

    /** 解析 token 得到用户 ID；非法/过期返回 null */
    private Long parseUserId(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        try {
            return Long.valueOf(JwtUtils.parseToken(token, jwtSecret).getSubject());
        } catch (Exception e) {
            return null;
        }
    }
}
