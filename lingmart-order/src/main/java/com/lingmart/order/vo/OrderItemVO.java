package com.lingmart.order.vo;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 订单明细视图。
 */
@Data
public class OrderItemVO {

    private Long spuId;

    private Long skuId;

    private String spuTitle;

    private String skuTitle;

    private String skuImage;

    /** 成交单价 */
    private BigDecimal price;

    /** 购买数量 */
    private Integer num;

    /** 小计金额 */
    private BigDecimal totalPrice;
}
