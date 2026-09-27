package com.lingmart.search.es;

import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.elasticsearch.annotations.Document;
import org.springframework.data.elasticsearch.annotations.Field;
import org.springframework.data.elasticsearch.annotations.FieldType;

import java.math.BigDecimal;

/**
 * 检索文档映射（与 product 服务写入 {@code lingmart_product} 索引的契约一致）。
 *
 * <p>仅存储检索所需字段；商品详情仍由 product 服务查 MySQL 返回。</p>
 */
@Data
@Document(indexName = "lingmart_product")
public class EsProduct {

    /** 商品SPU ID（文档主键） */
    @Id
    private String spuId;

    private Long shopId;

    private String shopName;

    /** 商品标题（IK 中文分词：索引用 ik_max_word 细粒度切分，检索用 ik_smart 粗粒度召回） */
    @Field(type = FieldType.Text, analyzer = "ik_max_word", searchAnalyzer = "ik_smart")
    private String title;

    /** 副标题/卖点 */
    @Field(type = FieldType.Text, analyzer = "ik_max_word", searchAnalyzer = "ik_smart")
    private String subtitle;

    private Long categoryId;

    private String categoryName;

    private Long brandId;

    private String brandName;

    /** 最低 SKU 价格 */
    private BigDecimal price;

    private String mainImage;

    /** 1=上架 */
    private Integer status;

    private Integer saleCount;
}
