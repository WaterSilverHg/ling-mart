package com.lingmart.common.dto;

import lombok.Data;

/**
 * 库存同步条目：卖家发布/更新商品时，由 product 服务经 Feign 同步给 ware。
 *
 * <p>对应 {@code ware_sku} 表；seller 声明的 SKU 库存以这里为准写入库存服务。</p>
 */
@Data
public class StockSyncItem {

    /** 商品SPU ID */
    private Long spuId;

    /** 商品SKU ID */
    private Long skuId;

    /** 所属店铺ID */
    private Long shopId;

    /** SKU名称 */
    private String title;

    /** 可售库存 */
    private Integer stock;
}
