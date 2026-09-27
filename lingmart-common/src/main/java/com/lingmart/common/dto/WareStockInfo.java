package com.lingmart.common.dto;

import lombok.Data;

/**
 * 库存信息：ware 服务返回的 SKU 库存快照（可售 / 锁定）。
 */
@Data
public class WareStockInfo {

    /** 商品SKU ID */
    private Long skuId;

    /** 可售库存 */
    private Integer stock;

    /** 锁定库存 */
    private Integer lockedStock;

    /** 不限量标记：1=不限量（0/缺省为限量）；无限量时忽略 stock 数字 */
    private Integer unlimited;
}
