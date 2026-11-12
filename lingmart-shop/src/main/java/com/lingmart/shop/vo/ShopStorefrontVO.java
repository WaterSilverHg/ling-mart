package com.lingmart.shop.vo;

import com.lingmart.common.dto.ShopProductItem;
import lombok.Data;

import java.util.List;

/**
 * 店铺主页视图（公开浏览）。
 *
 * <p>店铺信息 + 店主公开信息（头像/昵称，不含手机号——店铺主页不暴露联系方式，
 * 手机号仅在买家发起会话后于聊天窗口内展示）+ 在售商品列表。</p>
 */
@Data
public class ShopStorefrontVO {

    /** 店铺 ID */
    private Long id;

    /** 店铺名称 */
    private String shopName;

    /** 店铺 logo 地址 */
    private String shopLogo;

    /** 店铺描述 */
    private String description;

    /** 是否支持快递发货：1 支持 / 0 仅到店自提（到店自提始终可用） */
    private Integer supportExpress;

    /** 门店地址（自提点）；未配置时为 null */
    private String address;

    /** 店主公开信息（头像/昵称，手机号不外发） */
    private OwnerInfo owner;

    /** 在售商品（审核通过 + 上架） */
    private List<ShopProductItem> products;

    /** 店主公开信息 */
    @Data
    public static class OwnerInfo {
        private Long userId;
        private String nickname;
        private String avatar;
    }
}
