package com.lingmart.search.service;

import com.lingmart.search.vo.SearchResultVO;
import com.lingmart.search.vo.SearchShopResultVO;

import java.math.BigDecimal;

/**
 * 检索服务接口：商品检索 + 店铺检索（按命中商品聚合出店铺）。
 */
public interface SearchService {

    /**
     * 多条件检索（仅上架商品）。
     *
     * @param keyword    关键词（标题/副标题匹配，可为空）
     * @param categoryId 分类ID（可为空）
     * @param minPrice   最低价（可为空）
     * @param maxPrice   最高价（可为空）
     * @param sort       排序：price_asc / price_desc / sale_desc（默认相关度）
     * @param page       页码（从 1 起）
     * @param size       每页大小
     * @return 分页检索结果
     */
    SearchResultVO search(String keyword, Long categoryId,
                          BigDecimal minPrice, BigDecimal maxPrice,
                          String sort, long page, long size);

    /**
     * 店铺检索（按命中商品聚合出店铺，供「以店铺为入口」的搜索）。
     *
     * <p>检索条件与 {@link #search} 一致（上架 + 关键词 + 分类 + 价格区间），
     * 但返回的是命中的店铺集合而非商品：点击店铺进入其主页查看在售商品。</p>
     *
     * @param keyword    关键词（商品标题/副标题匹配，可为空）
     * @param categoryId 分类ID（可为空）
     * @param minPrice   最低价（可为空）
     * @param maxPrice   最高价（可为空）
     * @param page       页码（从 1 起）
     * @param size       每页大小
     * @return 分页店铺检索结果
     */
    SearchShopResultVO searchShops(String keyword, Long categoryId,
                                   BigDecimal minPrice, BigDecimal maxPrice,
                                   long page, long size);
}
