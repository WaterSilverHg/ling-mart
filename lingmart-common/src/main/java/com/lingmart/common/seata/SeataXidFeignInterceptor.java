package com.lingmart.common.seata;

import com.lingmart.common.constant.SeataConstants;
import feign.RequestInterceptor;
import feign.RequestTemplate;
import org.apache.seata.core.context.RootContext;
import org.springframework.util.StringUtils;

/**
 * Feign 客户端侧的 Seata XID 传播。
 *
 * <p>Seata 2.5.0 不为 OpenFeign 提供集成（其全部制品中无任何 Feign 相关类），
 * 而 Spring Cloud OpenFeign 会把容器中所有 {@link RequestInterceptor} 应用到每一个 Feign 客户端，
 * 因此这里补上客户端这一环：当调用线程处于全局事务中时，把 XID 写入请求头。</p>
 *
 * <p>被调方应用无需任何代码即可参与分支事务。</p>
 *
 * <p>仅在 {@code RootContext.getXID()} 非空（确实处于全局事务）且请求尚未携带该头时写入，
 * 避免把普通调用平白带上事务语义，也避免与其他拦截器重复设置。</p>
 */
public class SeataXidFeignInterceptor implements RequestInterceptor {

    @Override
    public void apply(RequestTemplate template) {
        String xid = RootContext.getXID();
        if (StringUtils.hasText(xid) && !template.headers().containsKey(SeataConstants.HEADER_TX_XID)) {
            template.header(SeataConstants.HEADER_TX_XID, xid);
        }
    }
}
