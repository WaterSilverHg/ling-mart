package com.lingmart.common.constant;

/**
 * Seata 分布式事务相关常量：跨服务传递全局事务 ID（XID）的请求头。
 *
 * <p>Seata 客户端不提供 Feign 集成，跨服务 XID 需要自行传递：调用方由
 * {@code SeataXidFeignInterceptor} 把 {@code RootContext.getXID()} 写入下述请求头；
 * 被调方无需任何代码——Seata 自带的 {@code JakartaTransactionPropagationInterceptor}
 * 读到该头后 {@code RootContext.bind()}，其数据源代理随即向 TC 注册分支事务。</p>
 *
 * <p><b>该常量必须与 {@code org.apache.seata.core.context.RootContext.KEY_XID} 一致</b>，
 * 由 {@code SeataConstantsTest} 断言防漂移。之所以放在本模块而不是各处硬编码：
 * 网关需要剥离外部请求携带的该头（防止伪造 XID 注入），而网关没有 Seata 依赖、
 * 无法引用 {@code RootContext}。</p>
 */
public final class SeataConstants {

    /** 跨服务传递全局事务 ID 的请求头（值同 {@code RootContext.KEY_XID}） */
    public static final String HEADER_TX_XID = "TX_XID";

    private SeataConstants() {
    }
}
