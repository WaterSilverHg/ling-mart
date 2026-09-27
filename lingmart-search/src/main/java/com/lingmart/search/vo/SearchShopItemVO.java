package com.lingmart.search.vo;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 店铺检索结果条目：由商品检索命中的 ES 文档按 shopId 聚合而来。
 *
 * <p>「首页以店铺为入口」模型下，检索返回店铺卡片而非商品：点击店铺进入其主页查看在售商品。</p>
 */
@Data
public class SearchShopItemVO {

    /** 店铺ID（点击进店用） */
    private Long shopId;

    /** 店铺名称 */
    private String shopName;

    /** 该店铺命中检索条件的在售商品数 */
    private int productCount;

    /** 命中商品的最低起售价（无则 null） */
    private BigDecimal priceFrom;

    /** 代表性商品主图（卡片视觉，店铺 logo 由店铺主页返回） */
    private String image;
}
