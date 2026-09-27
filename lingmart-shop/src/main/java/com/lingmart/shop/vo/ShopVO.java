package com.lingmart.shop.vo;

import lombok.Data;

/**
 * 店铺信息视图对象。
 */
@Data
public class ShopVO {

    /** 店铺 ID */
    private Long id;

    /** 店铺名称 */
    private String shopName;

    /** 店铺 logo 地址 */
    private String shopLogo;

    /** 店铺描述 */
    private String description;

    /** 营业状态：0 未启用，1 营业中，2 已禁用 */
    private Integer status;

    /** 审核状态：0 待审核，1 已通过，2 已拒绝，3 待重审（描述变更） */
    private Integer reviewStatus;

    /** 待审核的新店铺信息草稿(JSON)，宽松重审用 */
    private String draftContent;
}
