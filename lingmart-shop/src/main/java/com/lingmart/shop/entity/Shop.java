package com.lingmart.shop.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 店铺实体（对应表 lingmart_shop.shop）。
 *
 * <p>营业状态 {@code status}：0 未启用，1 营业中，2 已禁用；
 * 审核状态 {@code reviewStatus}：0 待审核，1 已通过，2 已拒绝，3 待重审（描述变更，宽松）。
 * 店铺随入驻申请创建，审核通过后营业；改店铺信息走宽松重审（旧信息先营业，通过后才生效）。</p>
 */
@Data
@TableName("shop")
public class Shop {

    /** 主键（雪花，应用侧分配） */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 店主用户 ID */
    private Long userId;

    /** 店铺名称（唯一） */
    private String shopName;

    /** 店铺 logo 地址 */
    private String shopLogo;

    /** 店铺描述 */
    private String description;

    /** 营业状态：0 未启用，1 营业中，2 已禁用 */
    private Integer status;

    /** 审核状态：0 待审核，1 已通过，2 已拒绝，3 待重审（描述变更） */
    private Integer reviewStatus;

    /** 待审核的新店铺信息草稿(JSON)：宽松重审，旧信息先营业、通过后才生效 */
    private String draftContent;

    /** 创建时间 */
    private LocalDateTime createTime;

    /** 更新时间 */
    private LocalDateTime updateTime;
}
