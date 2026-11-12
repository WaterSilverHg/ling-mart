package com.lingmart.shop.dto;

import lombok.Data;

/**
 * 店铺信息草稿（宽松重审用）：卖家改店铺名称/logo/描述时暂存的新内容。
 */
@Data
public class ShopDraftDTO {

    private String shopName;

    private String shopLogo;

    private String description;

    /** 是否支持快递发货（1 支持 / 0 仅自提）；null 视为沿用旧值 */
    private Integer supportExpress;

    /** 门店地址（自提点） */
    private String address;

    /** 门店联系电话 */
    private String contactPhone;
}
