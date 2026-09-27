package com.lingmart.common.dto;

import lombok.Data;

/**
 * 销量变更条目：支付成功后 order 服务通知 product 累加销量；退款审核通过时通知其回退销量。
 *
 * <p>方向由调用的端点决定（{@code /internal/product/sale} 累加、{@code /internal/product/sale/rollback}
 * 回退），{@link #num} 一律传正数：累加端点以 {@code Math.max(num, 0)} 钳制，回退端点取绝对值。</p>
 */
@Data
public class SaleItem {

    /** 商品SPU ID */
    private Long spuId;

    /** 商品SKU ID */
    private Long skuId;

    /** 数量（正数）：累加或回退的数值，方向由调用的端点决定 */
    private Integer num;
}
