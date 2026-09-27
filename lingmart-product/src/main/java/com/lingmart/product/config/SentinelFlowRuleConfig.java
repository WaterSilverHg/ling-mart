package com.lingmart.product.config;

import com.alibaba.csp.sentinel.adapter.spring.webmvc_v6x.callback.BlockExceptionHandler;
import com.alibaba.csp.sentinel.datasource.nacos.NacosDataSource;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRule;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRuleManager;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Properties;

/**
 * 商品服务 Sentinel 限流配置。
 *
 * <p>两类限流：① URL 级（webmvc v6x 过滤器自动按请求路径限流）② 方法级
 * （{@code @SentinelResource} 降级）。流控规则来源：
 * <ol>
 *   <li>启动时先加载 {@code classpath:sentinel-flow-rules.json} 作为本地兜底；</li>
 *   <li>再接入 Nacos 数据源（DataId {@code sentinel-product.json}）动态热更新——
 *       Nacos 有规则则覆盖，缺失/解析失败/不可达时沿用本地兜底，避免规则缺失导致 Sentinel 失防。</li>
 * </ol></p>
 */
@Slf4j
@Configuration
public class SentinelFlowRuleConfig {

    /** Nacos 中本服务流控规则 DataId（内容见 nacos-config/sentinel-product.json） */
    private static final String RULE_DATA_ID = "sentinel-product.json";
    private static final String RULE_GROUP = "DEFAULT_GROUP";

    @Value("${spring.cloud.nacos.config.server-addr:localhost:8848}")
    private String nacosServerAddr;

    @Value("${spring.cloud.nacos.config.username:nacos}")
    private String nacosUsername;

    @Value("${spring.cloud.nacos.config.password:nacos}")
    private String nacosPassword;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 统一限流响应：HTTP 429 + 前端约定的 R JSON 结构 */
    @Bean
    public BlockExceptionHandler blockExceptionHandler() {
        return (request, response, resourceName, ex) -> {
            response.setStatus(429);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"code\":429,\"msg\":\"请求过于频繁，请稍后再试\",\"data\":null}");
        };
    }

    /** 启动时加载本地兜底规则并接入 Nacos 动态数据源（规则缺失/解析失败仅告警，不阻断启动） */
    @Bean
    public ApplicationRunner sentinelFlowRuleLoader() {
        return args -> {
            // 1. 本地兜底规则（classpath 文件）
            List<FlowRule> fallback = loadClasspathRules();
            FlowRuleManager.loadRules(fallback);

            // 2. Nacos 动态数据源：有规则则覆盖，缺失/解析失败/不可达则沿用本地兜底
            try {
                Properties props = new Properties();
                props.setProperty("serverAddr", nacosServerAddr);
                props.setProperty("username", nacosUsername);
                props.setProperty("password", nacosPassword);
                NacosDataSource<List<FlowRule>> ds = new NacosDataSource<>(props, RULE_GROUP, RULE_DATA_ID,
                        source -> {
                            if (source == null || source.isBlank()) {
                                return fallback;
                            }
                            try {
                                return objectMapper.readValue(source, new TypeReference<List<FlowRule>>() {});
                            } catch (Exception e) {
                                log.error("解析 Nacos 限流规则失败，沿用本地兜底：{}", e.getMessage());
                                return fallback;
                            }
                        });
                FlowRuleManager.register2Property(ds.getProperty());
                log.info("Sentinel 流控规则已接入 Nacos 数据源：dataId={}", RULE_DATA_ID);
            } catch (Exception e) {
                log.warn("Sentinel Nacos 数据源初始化失败，保留本地规则：{}", e.getMessage());
            }
        };
    }

    /** 读取 classpath 规则作为兜底；文件缺失/解析失败返回空列表 */
    private List<FlowRule> loadClasspathRules() {
        try {
            String json = new String(new ClassPathResource("sentinel-flow-rules.json")
                    .getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            List<FlowRule> rules = objectMapper.readValue(json, new TypeReference<>() {});
            log.info("Sentinel 本地兜底规则已加载：{} 条", rules.size());
            return rules;
        } catch (Exception e) {
            log.warn("Sentinel 本地兜底规则加载失败（规则为空）：{}", e.getMessage());
            return List.of();
        }
    }
}
