package com.lingmart.chat.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.converter.MessageConverter;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

import java.util.List;

/**
 * WebSocket + STOMP 配置。
 *
 * <p>端点 {@code /ws} 经网关代理（前端连 ws://localhost:88/ws）。
 * 简单消息代理 {@code /topic}：同一会话的双方订阅 {@code /topic/conversation/{id}}，
 * 服务端持久化后 convertAndSend 广播给会话双方。</p>
 */
@Configuration
@EnableWebSocketMessageBroker
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final JwtChannelInterceptor jwtChannelInterceptor;
    private final ObjectMapper objectMapper;

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        // 简单内存代理：/topic 广播（会话），/queue 点对点（暂未用，保留扩展）
        registry.enableSimpleBroker("/topic", "/queue");
        // 客户端发送消息的目标前缀（/app/chat/send）
        registry.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws")
                // 开发期允许跨域；生产由网关统一收紧
                .setAllowedOriginPatterns("*");
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        // CONNECT 时校验 token 并注入 principal
        registration.interceptors(jwtChannelInterceptor);
    }

    @Override
    public boolean configureMessageConverters(List<MessageConverter> messageConverters) {
        // 与 REST 共用同一 ObjectMapper：装箱 Long 以字符串输出（雪花 id 超 JS 精确整数上限，
        // 若 STOMP 帧里仍是 number 会被浏览器静默截断）。聊天负载均为 JSON，直接使用本转换器。
        MappingJackson2MessageConverter converter = new MappingJackson2MessageConverter();
        converter.setObjectMapper(this.objectMapper);
        messageConverters.add(converter);
        return true;
    }
}
