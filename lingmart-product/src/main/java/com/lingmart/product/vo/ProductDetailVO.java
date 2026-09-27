package com.lingmart.product.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 商品详情视图对象（SPU + SKU 列表）。
 */
@Data
public class ProductDetailVO {

    /** 商品 ID */
    private Long spuId;

    /** 所属店铺 ID */
    private Long shopId;

    /** 店铺名称 */
    private String shopName;

    /** 商品标题 */
    private String title;

    /** 副标题/卖点 */
    private String subtitle;

    /** 分类 ID */
    private Long categoryId;

    /** 分类名称 */
    private String categoryName;

    /** 品牌 ID */
    private Long brandId;

    /** 品牌名称 */
    private String brandName;

    /** 主图地址 */
    private String mainImage;

    /** 商品详情 */
    private String description;

    /** 状态：0 下架，1 上架 */
    private Integer status;

    /** 审核状态：0待审核 1已通过 2已拒绝 3待重审 */
    private Integer reviewStatus;

    /** 销量 */
    private Integer saleCount;

    /** 最低SKU价格 */
    private BigDecimal price;

    /** SKU 列表 */
    private List<SkuDetailVO> skus;
}
