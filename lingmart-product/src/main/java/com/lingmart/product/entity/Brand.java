package com.lingmart.product.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 品牌实体。
 */
@Data
@TableName("brand")
public class Brand {

    /** 主键（雪花，应用侧分配） */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 品牌名称 */
    private String name;

    /** 品牌 logo 地址 */
    private String logo;

    /** 状态：1 启用，0 禁用 */
    private Integer status;

    /** 创建时间 */
    private LocalDateTime createTime;

    /** 更新时间 */
    private LocalDateTime updateTime;
}
