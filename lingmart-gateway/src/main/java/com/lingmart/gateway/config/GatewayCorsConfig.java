package com.lingmart.gateway.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsWebFilter;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;
import org.springframework.web.util.pattern.PathPatternParser;

/**
 * 网关跨域配置。
 *
 * <p>网关统一处理跨域，下游业务服务无需再配置 CORS。
 * 基于 WebFlux 的 {@link CorsWebFilter}，允许来源白名单可配置
 * （{@code CORS_ALLOWED_ORIGINS} 环境变量，逗号分隔；默认开发前端 localhost:5173）。</p>
 */
@Configuration
public class GatewayCorsConfig {

    /** 允许的跨域来源（逗号分隔）；生产设 CORS_ALLOWED_ORIGINS 覆盖 */
    @Value("${lingmart.gateway.cors-allowed-origins:http://localhost:5173}")
    private String[] allowedOrigins;

    /**
     * 跨域过滤器 Bean。
     *
     * @return WebFlux 跨域过滤器
     */
    @Bean
    public CorsWebFilter corsWebFilter() {
        CorsConfiguration config = new CorsConfiguration();
        // 白名单来源（生产用环境变量收紧）；带凭据场景须用 allowedOriginPattern
        for (String origin : allowedOrigins) {
            config.addAllowedOriginPattern(origin.trim());
        }
        config.addAllowedMethod("*");
        config.addAllowedHeader("*");
        // 暴露下载响应头：前端读取 Content-Disposition 获取导出文件名
        config.addExposedHeader("Content-Disposition");
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource(new PathPatternParser());
        source.registerCorsConfiguration("/**", config);
        return new CorsWebFilter(source);
    }
}
