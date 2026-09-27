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
}
