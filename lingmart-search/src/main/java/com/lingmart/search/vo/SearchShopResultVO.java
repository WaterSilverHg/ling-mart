package com.lingmart.search.vo;

import lombok.Data;

import java.util.List;

/**
 * 店铺检索结果分页视图。
 */
@Data
public class SearchShopResultVO {

    /** 当前页店铺列表（按命中商品聚合） */
    private List<SearchShopItemVO> list;

    /** 命中的店铺总数 */
    private long total;

    /** 当前页码（从 1 起） */
    private long page;

    /** 每页大小 */
    private long size;
}
