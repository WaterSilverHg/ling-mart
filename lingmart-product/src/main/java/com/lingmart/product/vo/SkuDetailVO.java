package com.lingmart.product.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * SKU 详情视图对象（含图片）。
 */
@Data
public class SkuDetailVO {

    /** SKU ID */
    private Long id;

    /** SKU 名称 */
    private String title;

    /** 价格（元） */
    private BigDecimal price;

    /**
     * 数量：对买家/卖家展示的「实时剩余件数」。
     *
     * <p>由仓储可用量回源填充（stock - locked_stock，最小 0）；NULL = 不限量，
     * 此时前端不展示件数。仓储不可用时的降级兜底为卖家声明量。</p>
     */
    private Integer stock;

    /** 是否不限量：true 时不展示件数、不计库存；false 时按 {@link #stock} 展示剩余/售罄 */
    private Boolean unlimited;

    /** 销量 */
    private Integer saleCount;

    /** 状态：0 下架，1 上架 */
    private Integer status;

    /** 图片地址列表 */
    private List<String> images;
}
