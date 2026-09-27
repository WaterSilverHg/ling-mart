package com.lingmart.search.controller;

import com.lingmart.common.result.R;
import com.lingmart.search.service.SearchService;
import com.lingmart.search.vo.SearchResultVO;
import com.lingmart.search.vo.SearchShopResultVO;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;

/**
 * 商品检索接口（经网关，需登录）。
 */
@RestController
@RequestMapping("/api/search")
@RequiredArgsConstructor
public class SearchController {

    private final SearchService searchService;

    /**
     * 多条件检索商品。
     *
     * @param keyword    关键词
     * @param categoryId 分类ID
     * @param minPrice   最低价
     * @param maxPrice   最高价
     * @param sort       排序：price_asc / price_desc / sale_desc
     * @param page       页码（默认 1）
     * @param size       每页大小（默认 10）
     * @return 分页检索结果
     */
    @GetMapping
    public R<SearchResultVO> search(@RequestParam(required = false) String keyword,
                                    @RequestParam(required = false) Long categoryId,
                                    @RequestParam(required = false) BigDecimal minPrice,
                                    @RequestParam(required = false) BigDecimal maxPrice,
                                    @RequestParam(required = false, defaultValue = "") String sort,
                                    @RequestParam(required = false, defaultValue = "1") long page,
                                    @RequestParam(required = false, defaultValue = "10") long size) {
        return R.ok(searchService.search(keyword, categoryId, minPrice, maxPrice, sort, page, size));
    }

    /**
     * 店铺检索（首页搜索框）：按命中商品聚合出店铺，点击店铺进入其主页查看在售商品。
     * 商品不再直接作为搜索结果显示。
     *
     * @param keyword    关键词（商品标题匹配，可为空）
     * @param categoryId 分类ID（可为空）
     * @param minPrice   最低价（可为空）
     * @param maxPrice   最高价（可为空）
     * @param page       页码（默认 1）
     * @param size       每页大小（默认 10）
     * @return 分页店铺检索结果
     */
    @GetMapping("/shops")
    public R<SearchShopResultVO> searchShops(@RequestParam(required = false) String keyword,
                                             @RequestParam(required = false) Long categoryId,
                                             @RequestParam(required = false) BigDecimal minPrice,
                                             @RequestParam(required = false) BigDecimal maxPrice,
                                             @RequestParam(required = false, defaultValue = "1") long page,
                                             @RequestParam(required = false, defaultValue = "10") long size) {
        return R.ok(searchService.searchShops(keyword, categoryId, minPrice, maxPrice, page, size));
    }
}
