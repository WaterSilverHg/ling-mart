package com.lingmart.ware.mq;

import com.lingmart.common.dto.OrderPaidMessage;
import com.lingmart.common.dto.StockLockItem;
import com.lingmart.common.dto.StockLockRequest;
import com.lingmart.ware.config.RabbitPaidConfig;
import com.lingmart.ware.service.WareService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 「订单支付成功」事件监听：异步扣减库存。
 *
 * <p>收到 lingmart-order 经 Outbox 发布的事件后，把该单锁定库存转真实售出。消息 durable、
 * 消费失败由容器重试、耗尽进死信队列 {@code order.paid.ware.dlq}；配合 Outbox 重发 + 本侧幂等
 * （同一 (order_sn, sku) 已扣过即跳过），保证“已支付未扣”最终被收敛且不会重复扣。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderPaidListener {

    private final WareService wareService;

    @RabbitListener(queues = RabbitPaidConfig.PAID_QUEUE)
    public void onOrderPaid(OrderPaidMessage message) {
        if (message == null || message.items() == null || message.items().isEmpty()) {
            return;
        }
        StockLockRequest request = new StockLockRequest();
        request.setOrderSn(message.orderSn());
        List<StockLockItem> items = new ArrayList<>();
        for (OrderPaidMessage.Item item : message.items()) {
            if (item == null || item.skuId() == null || item.num() == null) {
                continue;
            }
            StockLockItem lockItem = new StockLockItem();
            lockItem.setSkuId(item.skuId());
            lockItem.setShopId(item.shopId());
            lockItem.setNum(item.num());
            items.add(lockItem);
        }
        request.setItems(items);
        if (items.isEmpty()) {
            return;
        }
        wareService.deductStock(request);
        log.info("已消费订单支付事件并扣减库存：orderSn={}, items={}", message.orderSn(), items.size());
    }
}
