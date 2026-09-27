package com.lingmart.order.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 购物车实体。
 */
@Data
@TableName("cart")
public class Cart {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 买家用户ID */
    private Long buyerId;

    private Long spuId;

    private Long skuId;

    private Long shopId;

    private String spuTitle;

    private String skuTitle;

    private String skuImage;

    /** 单价 */
    private BigDecimal price;

    /** 数量 */
    private Integer num;

    /** 是否勾选：1勾选 0未勾选 */
    private Integer checked;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
