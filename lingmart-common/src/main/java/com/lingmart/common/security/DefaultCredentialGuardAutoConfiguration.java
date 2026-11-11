package com.lingmart.common.security;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

/**
 * 默认凭据守卫的自动配置。
 *
 * <p>仅当 {@code lingmart.security.strict-credentials=true} 时生效（默认关闭，
 * 避免打断本地零配置启动）。生产覆盖层 {@code docker-compose.prod.yml} 会为
 * 全部业务服务打开该开关；判断逻辑与检查项见 {@link DefaultCredentialGuard}。</p>
 */
@AutoConfiguration
@ConditionalOnProperty(name = "lingmart.security.strict-credentials", havingValue = "true")
public class DefaultCredentialGuardAutoConfiguration {

    /**
     * 注册守卫 Bean。它在 {@code afterPropertiesSet} 里做检查，
     * 命中默认凭据时抛出异常，令整个应用启动失败。
     *
     * @param environment 配置来源
     * @return 守卫实例
     */
    @Bean
    public DefaultCredentialGuard defaultCredentialGuard(Environment environment) {
        return new DefaultCredentialGuard(environment);
    }
}
