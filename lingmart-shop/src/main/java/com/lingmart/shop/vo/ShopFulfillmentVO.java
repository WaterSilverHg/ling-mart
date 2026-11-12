package com.lingmart.shop.vo;

import lombok.Data;

/**
 * 店铺履约能力视图（公开，结算页使用）。
 *
 * <p>只包含买家在结算时需要知道的履约信息：能不能快递、门店在哪、有事打哪个电话。
 * 与店铺主页（{@link ShopStorefrontVO}）刻意分开：主页是浏览场景，不暴露联系方式；
 * 结算是有明确交易意图的场景，门店电话属于必要的履约信息。</p>
 */
@Data
public class ShopFulfillmentVO {

    /** 店铺 ID */
    private Long shopId;

    /** 店铺名称 */
    private String shopName;

    /** 是否支持快递发货：1 支持 / 0 仅到店自提（到店自提始终可用） */
    private Integer supportExpress;

    /** 门店地址（自提点）；未配置时为 null */
    private String address;

    /** 门店联系电话；未配置时为 null */
    private String contactPhone;
}
