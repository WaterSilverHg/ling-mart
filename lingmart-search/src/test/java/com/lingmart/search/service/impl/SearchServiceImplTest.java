package com.lingmart.search.service.impl;

import com.alibaba.csp.sentinel.slots.block.BlockException;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRule;
import com.lingmart.search.es.EsProduct;
import com.lingmart.search.service.SearchService;
import com.lingmart.search.vo.SearchResultVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.query.Query;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 检索服务单元测试：空索引/无数据不报错、Sentinel 限流降级返回空结果。
 */
@ExtendWith(MockitoExtension.class)
class SearchServiceImplTest {

    @Mock
    private ElasticsearchOperations es;
    @Mock
    private StringRedisTemplate redisTemplate;
    @Mock
    private ValueOperations<String, String> valueOps;

    private SearchService service;

    @BeforeEach
    void setUp() {
        // 缓存命中路径 mock：默认 miss（返回 null），走 ES 查询
        lenient().when(redisTemplate.opsForValue()).thenReturn(valueOps);
        service = new SearchServiceImpl(es, redisTemplate, new ObjectMapper());
    }

    /** 核心回归：ES 索引不存在（从未上架商品）时返回空结果，而不是 500「系统繁忙」 */
    @Test
    void search_索引不存在返回空结果() {
        RuntimeException indexMissing = new RuntimeException(
                "Elasticsearch exception [type=index_not_found_exception, reason=no such index [lingmart-product]]");
        when(es.search(any(Query.class), eq(EsProduct.class))).thenThrow(indexMissing);

        SearchResultVO result = service.search("苹果", null, null, null, null, 1, 10);

        assertEquals(0, result.getTotal());
        assertTrue(result.getList().isEmpty());
        assertEquals(1, result.getPage());
        assertEquals(10, result.getSize());
    }

    /** Sentinel 降级：检索被限流时返回空结果，接口不报错 */
    @Test
    void searchBlock_限流降级返回空结果() {
        BlockException be = mock(BlockException.class);
        FlowRule rule = new FlowRule();
        rule.setCount(50.0);
        when(be.getRule()).thenReturn(rule);

        SearchResultVO result = SearchServiceImpl.searchBlock("苹果", null, null, null, null, 1, 10, be);

        assertEquals(0, result.getTotal());
        assertTrue(result.getList().isEmpty());
    }

    /** 降级与正常返回结构一致（分页字段不缺失） */
    @Test
    void searchBlock_分页字段保留() {
        BlockException be = mock(BlockException.class);
        when(be.getRule()).thenReturn(new FlowRule());

        SearchResultVO result = SearchServiceImpl.searchBlock(null, null, null, null, "price_asc", 3, 20, be);

        assertEquals(3, result.getPage());
        assertEquals(20, result.getSize());
    }
}
