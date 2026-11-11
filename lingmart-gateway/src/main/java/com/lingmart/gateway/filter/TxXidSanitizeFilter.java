package com.lingmart.gateway.filter;

import com.lingmart.common.constant.SeataConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 剥离外部请求携带的 Seata 全局事务 ID 请求头（{@code TX_XID}）。
 *
 * <p><b>为什么必须剥离</b>：Seata 的 {@code JakartaTransactionPropagationInterceptor}
 * 随 starter 默认注册且对所有路径生效，它无条件信任请求头里的 {@code TX_XID} 并绑定到
 * {@code RootContext}。若外部请求能带进来该头，下游服务的数据源代理就会拿着一个伪造的
 * 全局事务 ID 去 TC 注册分支——TC 查无此全局事务即报错，构成一个干净的骚扰面。</p>
 *
 * <p><b>为什么剥离不影响正常传播</b>：服务间调用走 {@code lb://服务名} 直连（如 order → ware），
 * 根本不经过网关；只有 lingmart-agent 经网关调业务接口，而它不参与全局事务。
 * 因此网关只需处理「外部入站」这一个方向即可。</p>
 *
 * <p>本过滤器独立成类而不并入 {@link AuthGlobalFilter}：后者对白名单路径会提前放行返回，
 * 耦合进去会导致白名单路径漏剥。这里取 {@link Ordered#HIGHEST_PRECEDENCE}，先于鉴权执行。</p>
 */
@Slf4j
@Component
public class TxXidSanitizeFilter implements GlobalFilter, Ordered {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        if (!request.getHeaders().containsKey(SeataConstants.HEADER_TX_XID)) {
            return chain.filter(exchange);
        }
        // 正常业务流量不会带该头经网关进来，出现即异常：记录后剥离（不中断请求，可用性优先）
        log.warn("外部请求携带了 {} 请求头，已剥离（该头仅用于服务间事务传播）：path={}",
                SeataConstants.HEADER_TX_XID, request.getURI().getPath());
        ServerHttpRequest sanitized = request.mutate()
                .headers(headers -> headers.remove(SeataConstants.HEADER_TX_XID))
                .build();
        return chain.filter(exchange.mutate().request(sanitized).build());
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
