package com.lingmart.order.mq;

import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 「不可路由消息」与发送方 {@link CorrelationData} 的关联表。
 *
 * <p><b>为什么需要它</b>：broker 只有在 {@code mandatory=true} 且消息找不到队列时才会
 * 回传 basic.return；而 Spring AMQP 的 returns 回调只拿到 {@link ReturnedMessage}，
 * 不会自动把它挂回当初那条 {@code CorrelationData}。这里用发送时写入的 {@code messageId}
 * （即 Outbox 的 eventId）做键，把两者接起来。</p>
 *
 * <p>顺序上 broker 会先发 basic.return、再发 basic.ack，调用方在 confirm 完成后检查
 * {@link CorrelationData#getReturned()} 是可靠的。</p>
 */
@Slf4j
@Component
public class ReturnedMessageTracker {

    private final Map<String, CorrelationData> inflight = new ConcurrentHashMap<>();

    /** 发送前登记；messageId 必须同时写进消息属性（见 OrderPaidOutboxRelay）。 */
    public void track(String messageId, CorrelationData correlationData) {
        inflight.put(messageId, correlationData);
    }

    /** 发送结束（无论成败）清理，避免长期占用内存。 */
    public void untrack(String messageId) {
        inflight.remove(messageId);
    }

    /**
     * RabbitTemplate 的 returns 回调入口：按 messageId 找回 CorrelationData 并标记「已被退回」。
     */
    public void onReturned(ReturnedMessage returned) {
        String messageId = returned.getMessage().getMessageProperties().getMessageId();
        if (messageId == null) {
            log.warn("收到退回消息但没有 messageId，无法关联发送方：exchange={}, routingKey={}, replyText={}",
                    returned.getExchange(), returned.getRoutingKey(), returned.getReplyText());
            return;
        }
        CorrelationData cd = inflight.remove(messageId);
        if (cd == null) {
            // 可能是已超时清理，或不是本类发送的消息（例如 RepublishMessageRecoverer）——只记录不报错
            log.warn("退回消息未找到在途记录（可能已超时/非 Outbox 发送）：messageId={}, exchange={}, routingKey={}",
                    messageId, returned.getExchange(), returned.getRoutingKey());
            return;
        }
        cd.setReturned(returned);
    }
}
