package com.lingmart.shop.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 入驻申请实体（对应表 lingmart_shop.shop_application）。
 *
 * <p>状态 {@code status}：0 待审核，1 已通过，2 已拒绝。
 * 审核留痕：审核通过后店铺启用、用户角色提升为卖家。</p>
 */
@Data
@TableName("shop_application")
public class ShopApplication {

    /** 主键（雪花，应用侧分配） */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 申请人用户 ID */
    private Long userId;

    /** 申请店铺名称 */
    private String shopName;

    /** 店铺 logo 地址 */
    private String shopLogo;

    /** 店铺描述 */
    private String description;

    /** 状态：0 待审核，1 已通过，2 已拒绝 */
    private Integer status;

    /** 拒绝原因 */
    private String rejectReason;

    /** 申请时间 */
    private LocalDateTime createTime;

    /** 更新时间 */
    private LocalDateTime updateTime;
}
