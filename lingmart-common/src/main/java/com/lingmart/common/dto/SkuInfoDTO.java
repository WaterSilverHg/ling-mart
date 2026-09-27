package com.lingmart.common.dto;

import lombok.Data;

import java.math.BigDecimal;

/**
 * SKU 信息契约：order 服务下单时经 Feign 从 product 获取商品快照。
 */
@Data
public class SkuInfoDTO {

    /** 商品SPU ID */
    private Long spuId;

    /** 商品SKU ID */
    private Long skuId;

    /** 所属店铺ID */
    private Long shopId;

    /** 商品标题 */
    private String spuTitle;

    /** SKU名称 */
    private String skuTitle;

    /** 单价 */
    private BigDecimal price;

    /** SKU首图 */
    private String image;
}
