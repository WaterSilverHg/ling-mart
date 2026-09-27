package com.lingmart.order.mq;

import com.lingmart.order.config.RabbitMqConfig;
import com.lingmart.order.service.OrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * 订单支付超时监听器。
 *
 * <p>下单投递的延迟消息 TTL 到期后经死信队列到达，消费者校验订单仍待支付则自动取消
 * （本地事务 + Feign 解锁库存，MQ 最终一致性场景，不参与 Seata）。</p>
 *
 * <p>失败处理：异常上抛交给 Spring Retry 拦截器按退避重试（3 次），耗尽后消息被拒绝进死信队列
 * {@code order.timeout.dlq}（见 {@link RabbitMqConfig}）；兜底扫描 {@code scanTimeoutOrders}
 * 仍会周期性取消超时订单，故消费链路整体失败不会丢单。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderTimeoutListener {

    private final OrderService orderService;

    /**
     * 处理超时订单。
     *
     * @param orderSn 订单号
     */
    @RabbitListener(queues = RabbitMqConfig.TIMEOUT_QUEUE)
    public void onTimeout(String orderSn) {
        orderService.cancelByTimeout(orderSn);
    }
}
