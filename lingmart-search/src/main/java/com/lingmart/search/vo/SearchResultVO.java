package com.lingmart.search.vo;

import com.lingmart.search.es.EsProduct;
import lombok.Data;

import java.util.List;

/**
 * 检索结果分页视图。
 */
@Data
public class SearchResultVO {

    /** 当前页数据（含高亮片段） */
    private List<SearchItemVO> list;

    /** 总命中数 */
    private long total;

    /** 当前页码（从 1 起） */
    private long page;

    /** 每页大小 */
    private long size;
}
