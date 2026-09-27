package com.lingmart.auth.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI 文档配置（springdoc）。
 *
 * <p>提供接口文档元信息与 JWT Bearer 安全方案声明，访问入口：</p>
 * <ul>
 *   <li>OpenAPI JSON：{@code http://localhost:8000/v3/api-docs}（可导入 Apifox）</li>
 *   <li>Swagger UI：{@code http://localhost:8000/swagger-ui.html}</li>
 * </ul>
 */
@Configuration
public class OpenApiConfig {

    /**
     * 构建 OpenAPI 文档描述。
     *
     * @return OpenAPI 实例
     */
    @Bean
    public OpenAPI lingmartOpenAPI() {
        // JWT Bearer 安全方案：网关透传 token 的请求头统一为 Authorization: Bearer <token>
        final String securityScheme = "bearerAuth";
        return new OpenAPI()
                .info(new Info()
                        .title("轻营 · 认证中心 API")
                        .description("用户注册 / 登录、JWT 签发、手机号验证码、当前用户信息")
                        .version("v0.0.1")
                        .license(new License().name("MIT")))
                .addSecurityItem(new SecurityRequirement().addList(securityScheme))
                .components(new Components().addSecuritySchemes(securityScheme,
                        new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")));
    }
}
