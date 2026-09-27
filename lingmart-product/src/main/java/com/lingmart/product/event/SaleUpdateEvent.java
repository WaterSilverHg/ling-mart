package com.lingmart.product.event;

import com.lingmart.common.dto.SaleItem;

import java.util.List;

/**
 * 销量已变更事件（在本地事务内发布：支付累加、退款回退均适用）。
 *
 * <p>DB 侧 sale_count 已同步变更；本事件由监听器在事务提交后（@TransactionalEventListener
 * AFTER_COMMIT + @Async）异步刷新 ES 销量，避免 ES/店名 Feign 网络 IO 拖住支付事务与 SPU 行锁。
 * ES 最终一致即可，失败由 EsProductService 写 es_write_retry 补偿表兜底。</p>
 *
 * @param items 本次销量条目
 */
public record SaleUpdateEvent(List<SaleItem> items) {
}
