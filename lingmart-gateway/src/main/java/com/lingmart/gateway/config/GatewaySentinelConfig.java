package com.lingmart.gateway.config;

import com.alibaba.csp.sentinel.adapter.gateway.common.rule.GatewayFlowRule;
import com.alibaba.csp.sentinel.adapter.gateway.common.rule.GatewayRuleManager;
import com.alibaba.csp.sentinel.adapter.gateway.sc.callback.GatewayCallbackManager;
import com.alibaba.csp.sentinel.datasource.nacos.NacosDataSource;
import com.alibaba.csp.sentinel.slots.block.RuleConstant;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.server.ServerResponse;

import java.util.HashSet;
import java.util.Properties;
import java.util.Set;

/**
 * 网关 Sentinel 限流配置。
 *
 * <p>路由级流量控制：为每个业务路由设置独立 QPS 阈值。规则来源：
 * <ol>
 *   <li>启动时先加载本地代码构建的兜底规则（阈值来自 {@code lingmart.gateway.sentinel.*}，可用环境变量覆盖）；</li>
 *   <li>再接入 Nacos 数据源（DataId {@code sentinel-gateway.json}，group DEFAULT_GROUP）动态热更新——
 *       Nacos 有规则则覆盖，缺失/解析失败/不可达时沿用本地兜底，避免规则缺失导致 Sentinel 失防。</li>
 * </ol>
 * 被限流时返回 HTTP 429 + 统一 R JSON（通过 {@link GatewayCallbackManager} 覆盖默认响应）。</p>
 */
@Slf4j
@Configuration
public class GatewaySentinelConfig {

    /** Nacos 中网关流控规则 DataId（内容见 nacos-config/sentinel-gateway.json） */
    private static final String RULE_DATA_ID = "sentinel-gateway.json";
    private static final String RULE_GROUP = "DEFAULT_GROUP";

    @Value("${spring.cloud.nacos.config.server-addr:localhost:8848}")
    private String nacosServerAddr;

    @Value("${spring.cloud.nacos.config.username:nacos}")
    private String nacosUsername;

    @Value("${spring.cloud.nacos.config.password:nacos}")
    private String nacosPassword;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 启动后执行：加载本地兜底路由流控规则 → 接入 Nacos 动态数据源 → 覆盖默认限流响应。
     *
     * @param authQps    认证中心路由 QPS
     * @param shopQps    店铺服务路由 QPS
     * @param productQps 商品服务路由 QPS
     * @param orderQps   订单/购物车路由 QPS
     * @param wareQps    仓储服务路由 QPS
     * @param searchQps  检索服务路由 QPS
     * @return 应用启动后执行的初始化器
     */
    @Bean
    public ApplicationRunner gatewaySentinelInitializer(
            @Value("${lingmart.gateway.sentinel.auth-route-qps:200}") double authQps,
            @Value("${lingmart.gateway.sentinel.shop-route-qps:100}") double shopQps,
            @Value("${lingmart.gateway.sentinel.product-route-qps:100}") double productQps,
            @Value("${lingmart.gateway.sentinel.order-route-qps:100}") double orderQps,
            @Value("${lingmart.gateway.sentinel.ware-route-qps:100}") double wareQps,
            @Value("${lingmart.gateway.sentinel.search-route-qps:50}") double searchQps) {
        return args -> {
            // 1. 本地兜底规则（阈值来自配置，可环境变量覆盖）
            Set<GatewayFlowRule> baseline = new HashSet<>();
            baseline.add(qpsRule("auth-route", authQps));
            baseline.add(qpsRule("shop-route", shopQps));
            baseline.add(qpsRule("product-route", productQps));
            baseline.add(qpsRule("order-route", orderQps));
            baseline.add(qpsRule("ware-route", wareQps));
            baseline.add(qpsRule("search-route", searchQps));
            GatewayRuleManager.loadRules(baseline);

            // 2. Nacos 动态数据源：有规则则覆盖，缺失/解析失败/不可达则沿用本地兜底
            try {
                Properties props = new Properties();
                props.setProperty("serverAddr", nacosServerAddr);
                props.setProperty("username", nacosUsername);
                props.setProperty("password", nacosPassword);
                NacosDataSource<Set<GatewayFlowRule>> ds = new NacosDataSource<>(props, RULE_GROUP, RULE_DATA_ID,
                        source -> {
                            if (source == null || source.isBlank()) {
                                return baseline;
                            }
                            try {
                                return objectMapper.readValue(source, new TypeReference<Set<GatewayFlowRule>>() {});
                            } catch (Exception e) {
                                log.error("解析 Nacos 网关限流规则失败，沿用本地兜底：{}", e.getMessage());
                                return baseline;
                            }
                        });
                GatewayRuleManager.register2Property(ds.getProperty());
                log.info("Sentinel 网关限流规则已接入 Nacos 数据源：dataId={}", RULE_DATA_ID);
            } catch (Exception e) {
                log.warn("Sentinel 网关 Nacos 数据源初始化失败，保留本地兜底规则：{}", e.getMessage());
            }

            GatewayCallbackManager.setBlockHandler((exchange, ex) ->
                    ServerResponse.status(HttpStatus.TOO_MANY_REQUESTS)
                            .contentType(MediaType.APPLICATION_JSON)
                            .bodyValue("{\"code\":429,\"msg\":\"请求过于频繁，请稍后再试\",\"data\":null}"));

            log.info("Sentinel 网关限流已就绪：auth={}, shop={}, product={}, order={}, ware={}, search={}",
                    authQps, shopQps, productQps, orderQps, wareQps, searchQps);
        };
    }

    /** 构造一条按路由 ID 的 QPS 流控规则（直接拒绝） */
    private GatewayFlowRule qpsRule(String routeId, double qps) {
        GatewayFlowRule rule = new GatewayFlowRule(routeId);
        rule.setGrade(RuleConstant.FLOW_GRADE_QPS);
        rule.setCount(qps);
        rule.setIntervalSec(1);
        return rule;
    }
}
