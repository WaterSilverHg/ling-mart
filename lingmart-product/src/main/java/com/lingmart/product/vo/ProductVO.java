package com.lingmart.product.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 商品列表项视图对象（列表页展示）。
 */
@Data
public class ProductVO {

    /** 商品 ID */
    private Long spuId;

    /** 商品标题 */
    private String title;

    /** 主图地址 */
    private String mainImage;

    /** 最低 SKU 价格 */
    private BigDecimal price;

    /** 销量 */
    private Integer saleCount;

    /** 状态：0 下架，1 上架 */
    private Integer status;

    /** 审核状态：0待审核 1已通过 2已拒绝 3待重审 */
    private Integer reviewStatus;

    /** 各 SKU 的实时数量（仅卖家「我的商品」填充；公开列表为空） */
    private List<SkuStockVO> stocks;
}
