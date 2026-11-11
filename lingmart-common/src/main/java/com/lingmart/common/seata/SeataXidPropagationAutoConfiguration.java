package com.lingmart.common.seata;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;

/**
 * Seata 跨服务 XID 传播（客户端侧）自动配置。
 *
 * <p>仅对同时具备 Feign 与 Seata 的服务生效（当前为 order / shop）。条件用字符串形式的类名判断，
 * 类不存在时条件不成立、自动配置被跳过，而不是抛出 {@code NoClassDefFoundError}；
 * auth / search / chat / agent / 网关不具备 Seata，product 在本次接入前也不具备，均自然跳过。</p>
 *
 * <p><b>服务端方向无需任何代码</b>：Seata 的 {@code SeataHttpAutoConfiguration}
 * （{@code matchIfMissing = true}，随 starter 默认开启）会注册
 * {@code JakartaTransactionPropagationInterceptor}，读取 {@code TX_XID} 头并绑定到 RootContext。</p>
 */
@AutoConfiguration
@ConditionalOnClass(name = {
        "feign.RequestInterceptor",
        "org.apache.seata.core.context.RootContext"
})
public class SeataXidPropagationAutoConfiguration {

    @Bean
    public SeataXidFeignInterceptor seataXidFeignInterceptor() {
        return new SeataXidFeignInterceptor();
    }
}
