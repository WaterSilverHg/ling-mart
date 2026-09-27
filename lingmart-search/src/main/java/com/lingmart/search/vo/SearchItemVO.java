package com.lingmart.search.vo;

import com.lingmart.search.es.EsProduct;
import lombok.Data;

/**
 * 检索条目视图：商品 + 关键词高亮片段（供前端展示）。
 */
@Data
public class SearchItemVO {

    /** 商品检索文档 */
    private EsProduct product;

    /** 标题高亮片段（含 &lt;em&gt; 标签） */
    private String titleHighlight;

    /** 副标题高亮片段 */
    private String subtitleHighlight;
}
