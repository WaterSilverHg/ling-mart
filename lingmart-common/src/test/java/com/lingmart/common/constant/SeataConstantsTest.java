package com.lingmart.common.constant;

import org.apache.seata.core.context.RootContext;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Seata 常量单元测试。
 *
 * <p>防漂移：{@link SeataConstants#HEADER_TX_XID} 必须与 Seata 的 {@code RootContext.KEY_XID}
 * 完全一致——调用方（{@code SeataXidFeignInterceptor}）按这个字面量写请求头，
 * 被调方由 Seata 自带的服务端拦截器按 {@code KEY_XID} 读，两端靠它对齐。
 * 一旦 Seata 升级改了值而这里没跟上，XID 传播会**静默失效**（不报任何错，只是分支不再注册），
 * 因此用断言把两处钉在一起。</p>
 */
class SeataConstantsTest {

    @Test
    void headerTxXid_shouldMatchSeataKeyXid() {
        assertEquals(RootContext.KEY_XID, SeataConstants.HEADER_TX_XID);
    }
}
