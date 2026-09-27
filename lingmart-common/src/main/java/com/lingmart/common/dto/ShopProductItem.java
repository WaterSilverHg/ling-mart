package com.lingmart.common.dto;

import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 店铺在售商品条目（跨服务契约：product 经内部接口返回给 shop 店铺主页聚合）。
 */
@Data
public class ShopProductItem implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 商品 SPU ID */
    private Long spuId;

    /** 商品标题 */
    private String title;

    /** 主图地址 */
    private String mainImage;

    /** 最低价（元） */
    private BigDecimal price;

    /** 销量 */
    private Integer saleCount;
}
