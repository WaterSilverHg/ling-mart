package com.lingmart.product.es;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.elasticsearch.annotations.Document;
import org.springframework.data.elasticsearch.annotations.Field;
import org.springframework.data.elasticsearch.annotations.FieldType;

import java.math.BigDecimal;

/**
 * 商品检索文档（写入 Elasticsearch）。
 *
 * <p>仅存储检索所需字段（ES 中商品的文档 ID 即 SPU ID）；详情查询仍走 MySQL。
 * 上架时写入，下架时删除。</p>
 */
@Data
@Document(indexName = "lingmart_product")
public class EsProduct {

    /** 商品 ID（SPU ID，作为 ES 文档 ID） */
    @Id
    private String spuId;

    /** 店铺 ID */
    private Long shopId;

    /** 店铺名称 */
    private String shopName;

    /** 商品标题（IK 中文分词：索引用 ik_max_word 细粒度切分，检索用 ik_smart 粗粒度召回） */
    @Field(type = FieldType.Text, analyzer = "ik_max_word", searchAnalyzer = "ik_smart")
    private String title;

    /** 副标题/卖点 */
    @Field(type = FieldType.Text, analyzer = "ik_max_word", searchAnalyzer = "ik_smart")
    private String subtitle;

    /** 分类 ID */
    private Long categoryId;

    /** 分类名称 */
    private String categoryName;

    /** 品牌 ID */
    private Long brandId;

    /** 品牌名称 */
    private String brandName;

    /** 最低 SKU 价格 */
    private BigDecimal price;

    /** 主图地址 */
    private String mainImage;

    /** 状态：1 上架 */
    private Integer status;

    /** 销量 */
    private Integer saleCount;
}
