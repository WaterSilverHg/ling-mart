package com.lingmart.search.config;

import com.alibaba.csp.sentinel.slots.block.flow.FlowRule;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Sentinel 限流规则文件可加载性测试（熔断/降级配置的兜底校验）。
 *
 * <p>规则文件损坏将导致运行时无任何限流保护，此测试确保 sentinel-flow-rules.json
 * 始终可解析为合法的 FlowRule 列表（与 SentinelFlowRuleConfig 加载逻辑一致）。</p>
 */
class SentinelFlowRuleConfigTest {

    @Test
    void 限流规则文件可解析为非空列表() throws Exception {
        String json = new String(new ClassPathResource("sentinel-flow-rules.json")
                .getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        List<FlowRule> rules = new ObjectMapper().readValue(json, new TypeReference<>() {});
        assertNotNull(rules);
        assertFalse(rules.isEmpty(), "限流规则不应为空");
        // 每条规则必须有资源名与阈值
        for (FlowRule rule : rules) {
            assertNotNull(rule.getResource());
            assertNotNull(rule.getCount());
        }
    }
}
