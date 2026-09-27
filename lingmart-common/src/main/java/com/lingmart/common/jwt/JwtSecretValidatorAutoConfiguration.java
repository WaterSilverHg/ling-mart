package com.lingmart.common.jwt;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

import java.nio.charset.StandardCharsets;

/**
 * JWT 密钥启动校验（自动配置）。
 *
 * <p>凡在配置中声明了 {@code lingmart.jwt.secret} 的服务（auth / gateway / chat 等）启动时都会执行校验：
 * <ul>
 *   <li>密钥长度 &lt; 32 字节 → 直接阻断启动（HS256 安全下限，防止使用弱密钥）；</li>
 *   <li>密钥等于仓库内置的默认开发值 → 输出 ERROR 级告警，提醒生产必须用 {@code JWT_SECRET} 环境变量覆盖。</li>
 * </ul>
 * 通过 {@code META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports} 注册，
 * 与组件扫描路径无关，因此网关（{@code scanBasePackages} 限定）也能加载。</p>
 */
@AutoConfiguration
@ConditionalOnProperty(name = "lingmart.jwt.secret")
public class JwtSecretValidatorAutoConfiguration {

    /** 仓库内置的默认开发密钥（各服务 application.yml 的兜底值），一旦命中必须告警 */
    public static final String DEFAULT_DEV_SECRET =
            "lingmart-secret-key-for-jwt-2026-please-change-in-production-env";

    @Bean
    public JwtSecretValidator jwtSecretValidator(@Value("${lingmart.jwt.secret}") String secret) {
        return new JwtSecretValidator(secret);
    }

    /** 启动期校验器：在容器就绪前执行，违规直接抛异常阻断启动 */
    static class JwtSecretValidator implements InitializingBean {
        private static final Logger log = LoggerFactory.getLogger(JwtSecretValidator.class);

        private final String secret;

        JwtSecretValidator(String secret) {
            this.secret = secret;
        }

        @Override
        public void afterPropertiesSet() {
            if (secret == null || secret.isBlank()) {
                throw new IllegalStateException(
                        "lingmart.jwt.secret 未配置，请通过 JWT_SECRET 环境变量提供签名密钥");
            }
            int bytes = secret.getBytes(StandardCharsets.UTF_8).length;
            if (bytes < 32) {
                throw new IllegalStateException("lingmart.jwt.secret 长度不足 32 字节（当前 "
                        + bytes + " 字节），HS256 签名不安全，请通过 JWT_SECRET 配置更长的密钥");
            }
            if (DEFAULT_DEV_SECRET.equals(secret)) {
                log.error("检测到 JWT 签名密钥仍为仓库内置的默认开发值，生产环境必须通过 JWT_SECRET "
                        + "环境变量覆盖，否则 Token 可被伪造，请立即处理！");
            }
        }
    }
}
