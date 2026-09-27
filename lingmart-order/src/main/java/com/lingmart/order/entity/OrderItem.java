package com.lingmart.order.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 订单明细实体。
 */
@Data
@TableName("order_item")
public class OrderItem {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long orderId;

    private String orderSn;

    private Long spuId;

    private Long skuId;

    private Long shopId;

    private String spuTitle;

    private String skuTitle;

    private String skuImage;

    /** 成交单价 */
    private BigDecimal price;

    /** 购买数量 */
    private Integer num;

    /** 小计金额 */
    private BigDecimal totalPrice;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
