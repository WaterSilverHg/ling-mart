package com.lingmart.order.mq;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lingmart.common.dto.OrderPaidMessage;
import com.lingmart.order.config.RabbitMqConfig;
import com.lingmart.order.entity.OrderPaidEvent;
import com.lingmart.order.mapper.OrderPaidEventMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 支付成功 Outbox 转发器：把待发事件发布到 MQ 并置为已发。
 *
 * <p>事件在支付事务内与订单状态同库落盘（status=0），本任务周期扫描发布。发布流程是
 * <b>「CAS 抢占（0→2）→ 投递 MQ → 置已发（2→1）」</b>：抢占失败的轮询直接跳过，因此
 * 多实例/多轮扫描不会把同一条事件并发投递两次（旧实现是「先发后标记」，重复投递窗口更大）。
 * 抢占后崩溃或投递异常的事件，由发布异常分支立即回置 0，或由 {@link #STUCK_SENDING_MINUTES}
 * 超时回收兜底，保证不会永久卡在「发送中」。</p>
 *
 * <p><b>投递结果如何确认（v0.37）</b>：开启 publisher confirm + returns 后，只有同时满足
 * 「broker 已 ack」且「没有被退回」才把事件置为已发送。此前 {@code convertAndSend} 只要不抛异常
 * 就当成功，broker 端「交换机不存在 / 绑定缺失」会静默丢弃消息，Outbox 却标记已发，
 * 扣库存只能靠流水补偿兜底。</p>
 *
 * <p><b>仍是至多一次投递的近似</b>：confirm 成功但状态推进失败时会重发，
 * 属 AMQP at-least-once 的固有语义——ware 消费幂等（同一 (order_sn, sku) 已扣过即跳过）兜底。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderPaidOutboxRelay {

    /** 「发送中」超过该分钟数视为进程中断遗留，回置待发送后重发 */
    private static final int STUCK_SENDING_MINUTES = 2;

    /** 等待 broker confirm 的超时（秒）。超时按失败处理、回置待发送，下轮重试。 */
    private static final long CONFIRM_TIMEOUT_SECONDS = 5;

    private final OrderPaidEventMapper orderPaidEventMapper;
    private final RabbitTemplate rabbitTemplate;
    private final ReturnedMessageTracker returnedMessageTracker;
    private final ObjectMapper objectMapper;

    @Scheduled(fixedDelayString = "${lingmart.order.outbox-interval-ms:5000}")
    public void relayPendingEvents() {
        try {
            doRelay();
        } catch (Exception e) {
            // 最常见的失败是存量库缺表（order_paid_event 是 v0.6 新增的，而 MySQL 容器的
            // docker-entrypoint-initdb.d 只在数据目录为空时执行一次，老库不会自动补）。
            // 不兜住的话每 5 秒会刷一条原始 SQL 异常，把真正有用的日志淹掉；这里改成一条可执行的提示。
            String msg = e.getMessage() == null ? "" : e.getMessage();
            if (msg.contains("order_paid_event")) {
                log.error("Outbox 表不存在：请在存量库执行 sql/upgrade/2026-09-06-order-paid-outbox.sql"
                        + "（或 scripts/reset-data.sh 重置库）；本轮跳过，下轮重试。err={}", msg);
            } else {
                log.error("Outbox 转发异常：本轮跳过，下轮重试。err={}", msg);
            }
        }
    }

    private void doRelay() {
        int recovered = orderPaidEventMapper.resetStuckSending(STUCK_SENDING_MINUTES);
        if (recovered > 0) {
            log.warn("Outbox 回收卡在「发送中」的事件 {} 条（超过 {} 分钟），本轮重发",
                    recovered, STUCK_SENDING_MINUTES);
        }
        List<OrderPaidEvent> pending = orderPaidEventMapper.selectList(new LambdaQueryWrapper<OrderPaidEvent>()
                .eq(OrderPaidEvent::getStatus, 0)
                .orderByAsc(OrderPaidEvent::getCreateTime)
                .last("LIMIT 100"));
        if (pending.isEmpty()) {
            return;
        }
        for (OrderPaidEvent event : pending) {
            // 抢占发送权：并发轮询/多实例下只有一个能由 0 置 2，其余跳过，避免重复投递
            if (orderPaidEventMapper.claimSending(event.getId()) == 0) {
                continue;
            }
            CorrelationData correlation = new CorrelationData(event.getEventId());
            returnedMessageTracker.track(event.getEventId(), correlation);
            try {
                OrderPaidMessage message = objectMapper.readValue(event.getPayload(), OrderPaidMessage.class);
                // messageId 参与 returns 回调的关联，必须与 track() 使用同一个值
                rabbitTemplate.convertAndSend(RabbitMqConfig.PAID_EXCHANGE, RabbitMqConfig.PAID_ROUTING_KEY, message,
                        m -> {
                            m.getMessageProperties().setMessageId(event.getEventId());
                            return m;
                        },
                        correlation);

                // 等 broker 确认：超时/nack 都按失败处理，回置待发送
                CorrelationData.Confirm confirm =
                        correlation.getFuture().get(CONFIRM_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                if (!confirm.isAck()) {
                    throw new AmqpException("broker 未确认（nack）：" + confirm.getReason());
                }
                // 已 ack 但被退回 = 消息没进任何队列（交换机/绑定缺失），同样不能标已发
                ReturnedMessage returned = correlation.getReturned();
                if (returned != null) {
                    throw new AmqpException("消息无法路由（已退回）：exchange=" + returned.getExchange()
                            + ", routingKey=" + returned.getRoutingKey()
                            + ", replyText=" + returned.getReplyText());
                }

                int marked = orderPaidEventMapper.markSent(event.getId());
                if (marked == 0) {
                    log.warn("Outbox 事件已投递但状态推进失败（可能已被超时回收后重发）：eventId={}, orderSn={}",
                            event.getEventId(), event.getOrderSn());
                } else {
                    log.info("Outbox 已发布支付事件：eventId={}, orderSn={}",
                            event.getEventId(), event.getOrderSn());
                }
            } catch (Exception e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                // 抢占后失败必须立刻回置，否则该事件要等 STUCK_SENDING_MINUTES 才可能被回收
                orderPaidEventMapper.resetToPending(event.getId());
                log.warn("Outbox 发布失败（已回置待发送，下轮重试）：eventId={}, orderSn={}, err={}",
                        event.getEventId(), event.getOrderSn(), e.getMessage());
            } finally {
                returnedMessageTracker.untrack(event.getEventId());
            }
        }
    }
}
