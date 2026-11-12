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

    /**
     * 是否支持快递发货：1 支持 / 0 仅到店自提。
     *
     * <p>项目定位是自营实体店，**到店自提是默认且始终可用**的履约方式；
     * 快递属于店铺可选能力，由店主在「我的店铺」里开关。买家下单时只能在
     * 店铺支持的方式中选择。</p>
     */
    private Integer supportExpress;

    /** 门店地址（买家自提点） */
    private String address;

    /** 门店联系电话（自提/售后联系用） */
    private String contactPhone;

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
