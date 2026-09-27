package com.lingmart.common.dto;

import java.util.List;

/**
 * 「订单支付成功」事件消息（order → ware 的扣减指令）。
 *
 * <p>由 lingmart-order 在支付成功事务内写入 Outbox、经 MQ 投递，lingmart-ware 消费后据此
 * 把该单的锁定库存转真实售出（幂等）。order 与 ware 共用同一类型，借助双方一致的
 * Jackson2JsonMessageConverter 传输。</p>
 *
 * @param orderSn 订单号
 * @param items   需扣减的 SKU 明细快照（skuId/shopId/num）
 */
public record OrderPaidMessage(
        String orderSn,
        List<Item> items) {

    public record Item(
            Long skuId,
            Long shopId,
            Integer num) {
    }
}
