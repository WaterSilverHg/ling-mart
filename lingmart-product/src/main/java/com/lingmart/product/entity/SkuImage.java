package com.lingmart.product.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * SKU 图片实体。
 */
@Data
@TableName("sku_images")
public class SkuImage {

    /** 主键（雪花，应用侧分配） */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 所属 SKU ID */
    private Long skuId;

    /** 图片地址 */
    private String imgUrl;

    /** 排序 */
    private Integer sort;
}
