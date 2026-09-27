package com.lingmart.order.mq;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lingmart.common.dto.OrderPaidMessage;
import com.lingmart.order.config.RabbitMqConfig;
import com.lingmart.order.entity.OrderPaidEvent;
import com.lingmart.order.mapper.OrderPaidEventMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 支付成功 Outbox 转发器：把待发事件发布到 MQ 并置为已发。
 *
 * <p>事件在支付事务内与订单状态同库落盘（status=0），本任务周期扫描发布。发布流程是
 * <b>「CAS 抢占（0→2）→ 投递 MQ → 置已发（2→1）」</b>：抢占失败的轮询直接跳过，因此
 * 多实例/多轮扫描不会把同一条事件并发投递两次（旧实现是「先发后标记」，重复投递窗口更大）。
 * 抢占后崩溃或投递异常的事件，由发布异常分支立即回置 0，或由 {@link #STUCK_SENDING_MINUTES}
 * 超时回收兜底，保证不会永久卡在「发送中」。</p>
 *
 * <p><b>仍是至多一次投递的近似</b>：{@code convertAndSend} 成功但状态推进失败时会重发，
 * 属 AMQP at-least-once 的固有语义——ware 消费幂等（同一 (order_sn, sku) 已扣过即跳过）兜底。
 * 另：未开启 publisher confirm（见 docs/todo-and-optimization.md），broker 端路由失败不会抛异常，
 * 静默丢弃的场景仍依赖 ware 侧库存流水补偿。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderPaidOutboxRelay {

    /** 「发送中」超过该分钟数视为进程中断遗留，回置待发送后重发 */
    private static final int STUCK_SENDING_MINUTES = 2;

    private final OrderPaidEventMapper orderPaidEventMapper;
    private final RabbitTemplate rabbitTemplate;
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
            try {
                OrderPaidMessage message = objectMapper.readValue(event.getPayload(), OrderPaidMessage.class);
                rabbitTemplate.convertAndSend(RabbitMqConfig.PAID_EXCHANGE, RabbitMqConfig.PAID_ROUTING_KEY, message);
                int marked = orderPaidEventMapper.markSent(event.getId());
                if (marked == 0) {
                    log.warn("Outbox 事件已投递但状态推进失败（可能已被超时回收后重发）：eventId={}, orderSn={}",
                            event.getEventId(), event.getOrderSn());
                } else {
                    log.info("Outbox 已发布支付事件：eventId={}, orderSn={}",
                            event.getEventId(), event.getOrderSn());
                }
            } catch (Exception e) {
                // 抢占后失败必须立刻回置，否则该事件要等 STUCK_SENDING_MINUTES 才可能被回收
                orderPaidEventMapper.resetToPending(event.getId());
                log.warn("Outbox 发布失败（已回置待发送，下轮重试）：eventId={}, orderSn={}, err={}",
                        event.getEventId(), event.getOrderSn(), e.getMessage());
            }
        }
    }
}
