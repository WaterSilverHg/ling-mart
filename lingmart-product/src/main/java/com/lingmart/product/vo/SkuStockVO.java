package com.lingmart.product.vo;

import lombok.Data;

/**
 * 卖家「我的商品」列表中的 SKU 数量视图（含实时剩余，供列表直接展示数量/不限量）。
 */
@Data
public class SkuStockVO {

    /** SKU ID */
    private Long skuId;

    /** SKU 名称 */
    private String title;

    /** 是否不限量：true 时不展示件数；false 时按 {@link #available} 展示剩余/售罄 */
    private Boolean unlimited;

    /** 实时剩余件数（仅限量时有效；0 = 售罄；不限量为 null） */
    private Integer available;
}
