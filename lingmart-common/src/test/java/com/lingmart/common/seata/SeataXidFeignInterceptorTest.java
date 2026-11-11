package com.lingmart.common.seata;

import com.lingmart.common.constant.SeataConstants;
import feign.RequestTemplate;
import org.apache.seata.core.context.RootContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * XID 传播拦截器单元测试。
 *
 * <p>不需要 TC 参与：{@code RootContext} 的绑定是线程内的，直接 bind 即可模拟「处于全局事务中」。
 * 本测试守的是传播链路最容易出错的一环——**没有全局事务时绝不能写该头**，
 * 否则被调方会拿一个空/伪造的 XID 去 TC 注册分支。</p>
 */
class SeataXidFeignInterceptorTest {

    private static final String XID = "127.0.0.1:8091:1234567890";

    private final SeataXidFeignInterceptor interceptor = new SeataXidFeignInterceptor();

    @AfterEach
    void tearDown() {
        // RootContext 基于 ThreadLocal，用例之间必须解绑，否则污染后续测试
        RootContext.unbind();
    }

    @Test
    void shouldWriteXidHeaderWhenInGlobalTransaction() {
        RootContext.bind(XID);
        RequestTemplate template = new RequestTemplate();

        interceptor.apply(template);

        assertEquals(List.of(XID), template.headers().get(SeataConstants.HEADER_TX_XID));
    }

    @Test
    void shouldNotWriteHeaderWhenNoGlobalTransaction() {
        RequestTemplate template = new RequestTemplate();

        interceptor.apply(template);

        assertFalse(template.headers().containsKey(SeataConstants.HEADER_TX_XID));
    }

    @Test
    void shouldNotOverrideExistingHeader() {
        RootContext.bind(XID);
        RequestTemplate template = new RequestTemplate();
        template.header(SeataConstants.HEADER_TX_XID, "xid-already-set");

        interceptor.apply(template);

        assertEquals(List.of("xid-already-set"), template.headers().get(SeataConstants.HEADER_TX_XID));
    }
}
