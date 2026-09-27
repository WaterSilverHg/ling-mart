package com.lingmart.common.dto;

import lombok.Data;

import java.util.List;

/**
 * 库存锁定/扣减/解锁请求：order 服务经 Feign 调用 ware 的统一契约。
 *
 * <p>同一 {@code orderSn} 下可含多个 SKU 条目；三个操作（lock/deduct/unlock）
 * 复用此结构，由 ware 内部接口路径区分。</p>
 */
@Data
public class StockLockRequest {

    /** 订单号 */
    private String orderSn;

    /** 待操作的 SKU 条目列表 */
    private List<StockLockItem> items;
}
