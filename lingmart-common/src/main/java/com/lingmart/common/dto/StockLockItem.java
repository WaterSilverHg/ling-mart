package com.lingmart.common.dto;

import lombok.Data;

/**
 * 库存锁定条目：下单（锁库存）/ 支付（扣减）/ 取消（解锁）时按 SKU 逐项操作。
 */
@Data
public class StockLockItem {

    /** 商品SKU ID */
    private Long skuId;

    /** 所属店铺ID（数据隔离） */
    private Long shopId;

    /** 数量 */
    private Integer num;
}
