package com.lingmart.gateway.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 网关 OpenAPI 聚合控制器。
 *
 * <p>springdoc 的网关聚合只会生成 Swagger UI 的下拉列表，并不会把各服务文档合并成
 * 单一 JSON。本控制器并行拉取 auth / shop / product 三份 OpenAPI 文档并合并，
 * 对外暴露 {@code GET /v3/api-docs/merged}，供 Apifox 等工具「一键导入」。</p>
 *
 * <p>合并规则：路径取并集（过滤 Feign 内部接口 {@code /internal/**}）、组件 schema 取并集、
 * 服务地址统一指向网关（{@code lingmart.gateway.public-addr}，默认 {@code http://localhost:88}），
 * 导入后可直接以网关为 Base URL 调用。</p>
 */
@RestController
@RequiredArgsConstructor
public class AggregatedOpenApiController {

    private final ObjectMapper objectMapper;

    /** 网关对外地址（拉取下游文档的 Base URL + 聚合文档的 server 地址，可经配置覆盖） */
    @Value("${lingmart.gateway.public-addr:http://localhost:88}")
    private String gatewayAddr;

    /** 各下游服务的文档地址（经网关自身路由转发，已加入 JWT 白名单） */
    private static final List<String> SERVICE_DOC_URLS = List.of(
            "/api/auth/v3/api-docs",
            "/api/shop/v3/api-docs",
            "/api/product/v3/api-docs",
            "/api/ware/v3/api-docs",
            "/api/order/v3/api-docs",
            "/api/search/v3/api-docs");

    /** 单个下游文档拉取超时（秒） */
    private static final Duration FETCH_TIMEOUT = Duration.ofSeconds(5);

    /**
     * 返回合并后的 OpenAPI 文档。
     *
     * @return 合并文档；某个服务不可用时其余服务仍正常返回
     */
    @GetMapping("/v3/api-docs/merged")
    public Mono<ObjectNode> merged() {
        WebClient client = WebClient.create(gatewayAddr);
        return Mono.zip(SERVICE_DOC_URLS.stream()
                        .map(url -> client.get().uri(url)
                                .retrieve().bodyToMono(ObjectNode.class)
                                .timeout(FETCH_TIMEOUT)
                                // 单个服务异常时以空文档兜底，不影响整体聚合
                                .onErrorResume(e -> Mono.just(objectMapper.createObjectNode())))
                        .toList(),
                docs -> docs)
                .map(this::merge);
    }

    /**
     * 合并多份 OpenAPI 文档。
     *
     * @param docs 各服务文档（含空文档兜底）
     * @return 合并结果
     */
    private ObjectNode merge(Object[] docs) {
        ObjectNode merged = objectMapper.createObjectNode();
        merged.put("openapi", "3.1.0");

        // 文档标题与版本
        ObjectNode info = objectMapper.createObjectNode();
        info.put("title", "轻营电商 · 全服务接口（聚合）");
        info.put("version", "v1.0");
        merged.set("info", info);

        // 服务地址统一指向网关，导入 Apifox 后直接以网关为 Base URL
        ObjectNode server = objectMapper.createObjectNode();
        server.put("url", gatewayAddr);
        server.put("description", "轻营 API 网关");
        merged.set("servers", objectMapper.createArrayNode().add(server));

        ObjectNode paths = objectMapper.createObjectNode();
        ObjectNode components = objectMapper.createObjectNode();
        ObjectNode schemas = objectMapper.createObjectNode();
        ArrayNode tags = objectMapper.createArrayNode();
        Map<String, Boolean> seenTags = new LinkedHashMap<>();

        for (Object obj : docs) {
            if (!(obj instanceof ObjectNode)) {
                continue;
            }
            ObjectNode doc = (ObjectNode) obj;

            // 合并路径（过滤 Feign 内部接口，避免导入后在 Apifox 中误调）
            JsonNode docPaths = doc.get("paths");
            if (docPaths != null && docPaths.isObject()) {
                docPaths.fields().forEachRemaining(entry -> {
                    if (!entry.getKey().startsWith("/internal/")) {
                        paths.set(entry.getKey(), entry.getValue());
                    }
                });
            }

            // 合并组件 schema（重名时后者覆盖，当前各服务无重名风险）
            JsonNode docSchemas = doc.path("components").path("schemas");
            if (docSchemas.isObject()) {
                schemas.setAll((ObjectNode) docSchemas);
            }

            // 合并标签（按 name 去重）
            JsonNode docTags = doc.get("tags");
            if (docTags != null && docTags.isArray()) {
                docTags.forEach(tag -> {
                    String name = tag.path("name").asText("");
                    if (!name.isEmpty() && seenTags.putIfAbsent(name, Boolean.TRUE) == null) {
                        tags.add(tag);
                    }
                });
            }
        }

        merged.set("paths", paths);
        components.set("schemas", schemas);
        merged.set("components", components);
        merged.set("tags", tags);
        return merged;
    }
}
