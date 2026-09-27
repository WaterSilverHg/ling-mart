package com.lingmart.ware.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 库存实体（ware_sku）。
 */
@Data
@TableName("ware_sku")
public class WareSku {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 商品SPU ID */
    private Long spuId;

    /** 商品SKU ID */
    private Long skuId;

    /** 所属店铺ID */
    private Long shopId;

    /** SKU名称 */
    private String title;

    /** 可售库存（不限量 SKU 固定为 0，仅占位） */
    private Integer stock;

    /** 锁定库存（下单未支付） */
    private Integer lockedStock;

    /** 不限量标记：1=不限量（库存锁定/扣减/解锁/回补全部跳过） */
    private Integer unlimited;

    /** 状态：1启用 0禁用 */
    private Integer status;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
