package com.lingmart.product.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 商品 SKU 实体（库存量单元）。
 */
@Data
@TableName("sku")
public class Sku {

    /** 主键（雪花，应用侧分配） */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 所属 SPU ID */
    private Long spuId;

    /** 所属店铺 ID（数据隔离关键字段） */
    private Long shopId;

    /** SKU 名称（如：黑色/128G） */
    private String title;

    /** 价格（元） */
    private BigDecimal price;

    /** 库存 */
    private Integer stock;

    /** 销量 */
    private Integer saleCount;

    /** 状态：0 下架，1 上架 */
    private Integer status;

    /** 创建时间 */
    private LocalDateTime createTime;

    /** 更新时间 */
    private LocalDateTime updateTime;
}
