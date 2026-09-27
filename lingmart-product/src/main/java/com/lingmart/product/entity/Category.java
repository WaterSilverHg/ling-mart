package com.lingmart.product.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 商品分类实体。
 */
@Data
@TableName("category")
public class Category {

    /** 主键（雪花，应用侧分配） */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 分类名称 */
    private String name;

    /** 父分类 ID（0 为顶级） */
    private Long parentId;

    /** 排序 */
    private Integer sort;

    /** 状态：1 启用，0 禁用 */
    private Integer status;

    /** 创建时间 */
    private LocalDateTime createTime;

    /** 更新时间 */
    private LocalDateTime updateTime;
}
