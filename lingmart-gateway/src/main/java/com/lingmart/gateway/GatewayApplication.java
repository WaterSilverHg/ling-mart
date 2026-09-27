package com.lingmart.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 轻营 · API 网关启动类。
 *
 * <p>基于 Spring Cloud Gateway（WebFlux），负责统一路由转发、JWT 鉴权与跨域配置。
 * 启动参数：{@code --server.port=88}。仅扫描本模块，避免加载 common 中的 Servlet
 * 全局异常处理器（网关是响应式应用，两者不兼容）。</p>
 */
@SpringBootApplication(scanBasePackages = "com.lingmart.gateway")
public class GatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }
}
