package com.lingmart.search.service.impl;

import co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery;
import org.springframework.data.elasticsearch.core.query.highlight.Highlight;
import com.alibaba.csp.sentinel.annotation.SentinelResource;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import com.alibaba.csp.sentinel.slots.block.degrade.DegradeRule;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRule;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lingmart.search.es.EsProduct;
import com.lingmart.search.service.SearchService;
import com.lingmart.search.vo.SearchItemVO;
import com.lingmart.search.vo.SearchResultVO;
import com.lingmart.search.vo.SearchShopItemVO;
import com.lingmart.search.vo.SearchShopResultVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.elasticsearch.client.elc.NativeQuery;
import org.springframework.data.elasticsearch.client.elc.NativeQueryBuilder;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.SearchHit;
import org.springframework.data.elasticsearch.core.SearchHits;
import org.springframework.data.elasticsearch.core.query.HighlightQuery;
import org.springframework.data.elasticsearch.core.query.highlight.HighlightField;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 检索服务实现：基于 NativeQuery 组合多条件查询，仅返回上架商品。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SearchServiceImpl implements SearchService {

    private static final int ON_SHELF = 1;

    /** 店铺聚合一次拉取的最大命中文档数（聚合成店铺后内存分页，C2C 规模足够） */
    private static final int SHOP_AGG_FETCH_SIZE = 1000;

    /** 热点关键词缓存 key 前缀 */
    private static final String CACHE_PREFIX = "lingmart:search:hot:";

    /** 热点关键词缓存 TTL（秒）：纯关键词检索结果短缓存，兼顾 ES 压力与数据新鲜度 */
    private static final long CACHE_TTL_SECONDS = 300;

    private final ElasticsearchOperations es;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    @Override
    @SentinelResource(value = "searchProduct", blockHandler = "searchBlock")
    public SearchResultVO search(String keyword, Long categoryId,
                                 BigDecimal minPrice, BigDecimal maxPrice,
                                 String sort, long page, long size) {
        long safePage = Math.max(page, 1);
        int safeSize = (int) Math.min(Math.max(size, 1), 100);

        // 热点关键词缓存：仅纯关键词检索（无过滤/排序）走缓存，命中直接返回
        if (cacheable(keyword, categoryId, minPrice, maxPrice, sort)) {
            SearchResultVO cached = getCached(keyword, safePage, safeSize);
            if (cached != null) {
                log.info("检索命中热点缓存：keyword={}, total={}", keyword, cached.getTotal());
                return cached;
            }
        }

        // 基础过滤（商品检索与店铺检索共用）
        BoolQuery.Builder bool = buildBaseQuery(keyword, categoryId, minPrice, maxPrice);

        NativeQueryBuilder queryBuilder = NativeQuery.builder()
                .withQuery(bool.build()._toQuery());

        if (keyword != null && !keyword.isBlank()) {
            List<HighlightField> fields = List.of(
                    new HighlightField("title"),
                    new HighlightField("subtitle")
            );
            Highlight highlight = new Highlight(fields);
            HighlightQuery highlightQuery = new HighlightQuery(highlight, null);
            queryBuilder.withHighlightQuery(highlightQuery);
        }
        // 排序（默认相关度）
        if ("price_asc".equals(sort)) {
            queryBuilder.withSort(Sort.by(Sort.Order.asc("price")));
        } else if ("price_desc".equals(sort)) {
            queryBuilder.withSort(Sort.by(Sort.Order.desc("price")));
        } else if ("sale_desc".equals(sort)) {
            queryBuilder.withSort(Sort.by(Sort.Order.desc("saleCount")));
        }

        queryBuilder.withPageable(PageRequest.of((int) (safePage - 1), safeSize));

        SearchHits<EsProduct> hits;
        try {
            hits = es.search(queryBuilder.build(), EsProduct.class);
        } catch (Exception e) {
            // 空数据场景：索引尚未创建（从未上架过商品）或索引不存在时，ES 抛 index_not_found，
            // 属于「查无数据」而非系统故障，应返回空结果而不是 500「系统繁忙」
            if (isIndexMissing(e)) {
                log.info("ES 索引不存在或暂无数据，返回空检索结果：keyword={}, categoryId={}", keyword, categoryId);
                return emptyResult(safePage, safeSize);
            }
            throw e;
        }

        SearchResultVO result = new SearchResultVO();
        result.setList(hits.getSearchHits().stream().map(hit -> {
            SearchItemVO vo = new SearchItemVO();
            vo.setProduct(hit.getContent());
            vo.setTitleHighlight(highlightFragment(hit, "title"));
            vo.setSubtitleHighlight(highlightFragment(hit, "subtitle"));
            return vo;
        }).toList());
        result.setTotal(hits.getTotalHits());
        result.setPage(safePage);
        result.setSize(safeSize);
        log.info("检索：keyword={}, categoryId={}, total={}", keyword, categoryId, result.getTotal());
        // 有结果才写缓存（避免「新上架商品」被空缓存短暂遮挡）
        if (cacheable(keyword, categoryId, minPrice, maxPrice, sort) && result.getTotal() > 0) {
            putCache(keyword, result, safePage, safeSize);
        }
        return result;
    }

    /**
     * 构建检索基础查询：仅上架 + 关键词（标题匹配）+ 分类过滤 + 价格区间。
     * 商品检索与店铺检索共用，避免两套查询逻辑漂移。
     */
    private BoolQuery.Builder buildBaseQuery(String keyword, Long categoryId,
                                             BigDecimal minPrice, BigDecimal maxPrice) {
        BoolQuery.Builder bool = new BoolQuery.Builder();
        bool.filter(f -> f.term(t -> t.field("status").value(ON_SHELF)));
        // 关键词：标题匹配
        if (keyword != null && !keyword.isBlank()) {
            bool.must(m -> m.match(mt -> mt.field("title").query(keyword)));
        }
        // 分类过滤
        if (categoryId != null) {
            bool.filter(f -> f.term(t -> t.field("categoryId").value(categoryId)));
        }
        // 价格区间（RangeQuery 为密封类型，数值区间用 number 变体；其 gte/lte 直接收 Double）
        if (minPrice != null && maxPrice != null) {
            bool.filter(f -> f.range(r -> r.number(n -> n.field("price").gte(minPrice.doubleValue()).lte(maxPrice.doubleValue()))));
        } else if (minPrice != null) {
            bool.filter(f -> f.range(r -> r.number(n -> n.field("price").gte(minPrice.doubleValue()))));
        } else if (maxPrice != null) {
            bool.filter(f -> f.range(r -> r.number(n -> n.field("price").lte(maxPrice.doubleValue()))));
        }
        return bool;
    }

    @Override
    @SentinelResource(value = "searchShops", blockHandler = "searchShopsBlock")
    public SearchShopResultVO searchShops(String keyword, Long categoryId,
                                          BigDecimal minPrice, BigDecimal maxPrice,
                                          long page, long size) {
        long safePage = Math.max(page, 1);
        int safeSize = (int) Math.min(Math.max(size, 1), 100);

        // 聚合店铺需要先拉回足够多的命中文档（最多 1000 条），再按 shopId 分组后内存分页
        NativeQueryBuilder queryBuilder = NativeQuery.builder()
                .withQuery(buildBaseQuery(keyword, categoryId, minPrice, maxPrice).build()._toQuery())
                .withPageable(PageRequest.of(0, SHOP_AGG_FETCH_SIZE));

        SearchHits<EsProduct> hits;
        try {
            hits = es.search(queryBuilder.build(), EsProduct.class);
        } catch (Exception e) {
            if (isIndexMissing(e)) {
                log.info("店铺检索：ES 索引不存在或暂无数据，返回空结果 keyword={}", keyword);
                return emptyShopResult(safePage, safeSize);
            }
            throw e;
        }

        // 按 shopId 聚合：店铺名、命中商品数、最低起售价、代表性主图
        Map<Long, SearchShopItemVO> agg = new LinkedHashMap<>();
        for (SearchHit<EsProduct> hit : hits.getSearchHits()) {
            EsProduct p = hit.getContent();
            if (p == null || p.getShopId() == null) {
                continue;
            }
            SearchShopItemVO vo = agg.computeIfAbsent(p.getShopId(), id -> {
                SearchShopItemVO v = new SearchShopItemVO();
                v.setShopId(id);
                v.setShopName(p.getShopName());
                return v;
            });
            vo.setProductCount(vo.getProductCount() + 1);
            if (p.getPrice() != null && (vo.getPriceFrom() == null || p.getPrice().compareTo(vo.getPriceFrom()) < 0)) {
                vo.setPriceFrom(p.getPrice());
            }
            if (vo.getImage() == null) {
                vo.setImage(p.getMainImage());
            }
        }

        List<SearchShopItemVO> all = List.copyOf(agg.values());
        long total = all.size();
        int from = (int) ((safePage - 1) * safeSize);
        List<SearchShopItemVO> pageList = from >= all.size()
                ? List.of()
                : all.subList(from, Math.min(from + safeSize, all.size()));

        SearchShopResultVO result = new SearchShopResultVO();
        result.setList(pageList);
        result.setTotal(total);
        result.setPage(safePage);
        result.setSize(safeSize);
        log.info("店铺检索：keyword={}, categoryId={}, shopCount={}", keyword, categoryId, total);
        return result;
    }

    /** 店铺检索空结果（无数据/索引缺失/限流降级共用） */
    private SearchShopResultVO emptyShopResult(long page, long size) {
        SearchShopResultVO result = new SearchShopResultVO();
        result.setList(List.of());
        result.setTotal(0);
        result.setPage(page);
        result.setSize(size);
        return result;
    }

    /**
     * Sentinel 限流降级：返回空店铺检索结果，接口不报错。
     * 签名须与 {@link #searchShops} 一致并追加 BlockException。
     */
    public static SearchShopResultVO searchShopsBlock(String keyword, Long categoryId,
                                                      BigDecimal minPrice, BigDecimal maxPrice,
                                                      long page, long size, BlockException ex) {
        log.warn("店铺检索被限流降级：keyword={}", keyword);
        SearchShopResultVO result = new SearchShopResultVO();
        result.setList(List.of());
        result.setTotal(0);
        result.setPage(Math.max(page, 1));
        result.setSize(Math.min(Math.max(size, 1), 100));
        return result;
    }

    /**
     * 是否走热点关键词缓存：仅纯关键词检索（无分类/价格过滤、无排序），
     * 避免把带过滤的组合查询（冷路径）也灌入 Redis。
     */
    private boolean cacheable(String keyword, Long categoryId, BigDecimal minPrice, BigDecimal maxPrice, String sort) {
        return keyword != null && !keyword.isBlank()
                && categoryId == null && minPrice == null && maxPrice == null
                && (sort == null || sort.isBlank());
    }

    private String cacheKey(String keyword, long page, long size) {
        return CACHE_PREFIX + keyword.trim() + ":" + page + ":" + size;
    }

    /** 读缓存（fail-open：Redis 异常返回 null 走 ES） */
    private SearchResultVO getCached(String keyword, long page, long size) {
        try {
            String json = redisTemplate.opsForValue().get(cacheKey(keyword, page, size));
            return json == null ? null : objectMapper.readValue(json, SearchResultVO.class);
        } catch (Exception e) {
            log.warn("读取检索缓存失败（忽略，走 ES）：keyword={}, err={}", keyword, e.getMessage());
            return null;
        }
    }

    /** 写缓存（fail-open：Redis 异常仅告警，不影响响应） */
    private void putCache(String keyword, SearchResultVO result, long page, long size) {
        try {
            redisTemplate.opsForValue().set(cacheKey(keyword, page, size),
                    objectMapper.writeValueAsString(result), Duration.ofSeconds(CACHE_TTL_SECONDS));
        } catch (Exception e) {
            log.warn("写入检索缓存失败（忽略）：keyword={}, err={}", keyword, e.getMessage());
        }
    }

    /** 空检索结果（无数据/索引缺失时返回，接口不报错） */
    private SearchResultVO emptyResult(long page, long size) {
        SearchResultVO result = new SearchResultVO();
        result.setList(List.of());
        result.setTotal(0);
        result.setPage(page);
        result.setSize(size);
        return result;
    }

    /** 沿异常链判断是否属于「索引不存在」（index_not_found_exception / no such index / not_found_exception） */
    private boolean isIndexMissing(Exception e) {
        Throwable t = e;
        while (t != null) {
            String msg = t.getMessage();
            if (msg != null && (msg.contains("index_not_found_exception")
                    || msg.contains("no such index")
                    || msg.contains("not_found_exception"))) {
                return true;
            }
            t = t.getCause();
        }
        return false;
    }

    /** 取某字段的高亮片段（无则返回 null） */
    private String highlightFragment(SearchHit<EsProduct> hit, String field) {
        List<String> fragments = hit.getHighlightFields().get(field);
        return fragments == null || fragments.isEmpty() ? null : fragments.get(0);
    }

    /**
     * Sentinel 限流降级：返回空检索结果，接口不报错。
     * 签名须与 {@link #search} 一致并追加 BlockException。
     */
    public static SearchResultVO searchBlock(String keyword, Long categoryId,
                                             BigDecimal minPrice, BigDecimal maxPrice,
                                             String sort, long page, long size,
                                             BlockException ex) {
        SearchResultVO result = new SearchResultVO();
        result.setList(List.of());
        result.setTotal(0);
        result.setPage(Math.max(page, 1));
        result.setSize(Math.min(Math.max(size, 1), 100));
        double qpsLimit = 0.0;
        if (ex.getRule() instanceof FlowRule) {
            qpsLimit = ((FlowRule) ex.getRule()).getCount();
        } else if (ex.getRule() instanceof DegradeRule) {
            qpsLimit = ((DegradeRule) ex.getRule()).getCount();
        }
        log.warn("检索被限流降级：keyword={}, qpsLimit={}", keyword, qpsLimit);
        return result;
    }
}
