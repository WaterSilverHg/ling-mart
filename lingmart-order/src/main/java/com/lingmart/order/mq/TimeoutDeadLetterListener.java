package com.lingmart.order.mq;

import com.lingmart.order.config.RabbitMqConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * 订单超时消费死信监听器（告警/人工介入点）。
 *
 * <p>超时取消消息重试耗尽进入死信队列后触发：说明该订单连续多次取消失败（如仓储服务持续不可用），
 * 记 ERROR 日志并提示依赖兜底扫描；生产可在此接告警（邮件/Webhook）。订单不会丢——
 * {@code scanTimeoutOrders} 定时兜底仍会取消超时订单。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TimeoutDeadLetterListener {

    /**
     * 死信消息处理：仅告警，不重放（避免死信循环）。
     *
     * @param orderSn 订单号
     */
    @RabbitListener(queues = RabbitMqConfig.TIMEOUT_DLQ)
    public void onDeadLetter(String orderSn) {
        log.error("订单超时取消进入死信（重试耗尽，待人工介入/依赖兜底扫描）：orderSn={}", orderSn);
    }
}
