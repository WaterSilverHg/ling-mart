package com.lingmart.common.dto;

import lombok.Data;

/**
 * 店铺信息（跨服务契约 DTO）。
 *
 * <p>由 lingmart-shop 通过内部接口暴露给其他服务（如 lingmart-product
 * 通过 Feign 获取当前用户店铺），避免各服务直接依赖 shop 实体。</p>
 */
@Data
public class ShopInfo {

    /** 店铺 ID */
    private Long id;

    /** 店主用户 ID */
    private Long userId;

    /** 店铺名称 */
    private String shopName;

    /** 店铺 logo 地址 */
    private String shopLogo;

    /** 店铺状态：0 待审核，1 已启用，2 已禁用 */
    private Integer status;

    /** 是否支持快递发货：1 支持 / 0 仅到店自提 */
    private Integer supportExpress;

    /** 门店地址（买家自提点） */
    private String address;

    /** 门店联系电话（自提/售后联系用） */
    private String contactPhone;
}
