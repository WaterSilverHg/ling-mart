package com.lingmart.common.security;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.env.Environment;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 默认凭据守卫：启动时检查仍在使用仓库内置默认值的关键凭据，命中即拒绝启动。
 *
 * <p><b>为什么需要它</b>：项目里所有敏感配置都写成 {@code ${环境变量:默认值}} 的形式，
 * 好处是本地零配置可跑；代价是**忘记覆盖时不会报任何错**——服务照常启动，
 * 用的是 {@code admin123}、{@code root}、{@code nacos} 这些公开可见的口令。
 * 这类问题只会在被扫描或被登录时才发现，属于「静默失败」，必须在启动期暴露。</p>
 *
 * <p><b>为什么默认关闭</b>：本地 {@code java -jar} 与 docker compose 开发流本来就依赖这些
 * 默认值，默认开启会把所有人的日常启动流程打断。因此由
 * {@code lingmart.security.strict-credentials=true} 显式开启，
 * 生产覆盖层 {@code docker-compose.prod.yml} 已为全部服务设置该开关。</p>
 *
 * <p><b>一次报全</b>：不在第一个违规处就抛，而是收集完整清单再抛出，
 * 避免运维改一个、重启一次、再发现下一个的往返。</p>
 */
public class DefaultCredentialGuard implements InitializingBean {

    /** 仓库内置的 JWT 默认密钥特征串（出现过即视为已泄露） */
    private static final String JWT_DEFAULT_MARKER = "please-change-in-production-env";

    /** HS256 要求的最小密钥长度（字节） */
    private static final int JWT_MIN_LENGTH = 32;

    /** 需要检查的「属性名 → 视为默认值的取值集合 → 人话描述」 */
    private static final List<CredentialCheck> CHECKS = List.of(
            new CredentialCheck("spring.datasource.password", Set.of("", "root"), "数据库口令"),
            new CredentialCheck("spring.cloud.nacos.config.password", Set.of("nacos"), "Nacos 配置中心口令"),
            new CredentialCheck("spring.cloud.nacos.discovery.password", Set.of("nacos"), "Nacos 注册中心口令"),
            new CredentialCheck("spring.rabbitmq.password", Set.of("guest"), "RabbitMQ 口令"),
            new CredentialCheck("lingmart.admin.password", Set.of("admin123"), "预置管理员口令"),
            new CredentialCheck("lingmart.auth.dev-code-enabled", Set.of("true"), "验证码明文开关（必须关闭）")
    );

    /** 配置来源 */
    private final Environment environment;

    public DefaultCredentialGuard(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void afterPropertiesSet() {
        List<String> violations = new ArrayList<>();
        checkJwtSecret(violations);
        for (CredentialCheck check : CHECKS) {
            checkProperty(check, violations);
        }
        if (!violations.isEmpty()) {
            throw new IllegalStateException(
                    "检测到生产环境仍在使用默认凭据，启动已中止。请通过环境变量覆盖以下配置后再启动："
                            + String.join("；", violations)
                            + "。（如确需以此配置启动，置 lingmart.security.strict-credentials=false，"
                            + "但这等同于接受上述风险。）");
        }
    }

    /**
     * 检查 JWT 密钥：既可能是仓库内置默认值，也可能是长度不足 32 字节的弱密钥。
     *
     * @param violations 违规清单（收集用）
     */
    private void checkJwtSecret(List<String> violations) {
        String secret = environment.getProperty("lingmart.jwt.secret");
        if (secret == null) {
            return;
        }
        if (secret.contains(JWT_DEFAULT_MARKER)) {
            violations.add("JWT 签名密钥 lingmart.jwt.secret（仓库内置默认值）");
        } else if (secret.getBytes(java.nio.charset.StandardCharsets.UTF_8).length < JWT_MIN_LENGTH) {
            violations.add("JWT 签名密钥 lingmart.jwt.secret（长度不足 " + JWT_MIN_LENGTH + " 字节）");
        }
    }

    /**
     * 检查单个配置项是否仍为默认值；属性不存在（该服务不涉及此配置）时跳过。
     *
     * @param check      检查项定义
     * @param violations 违规清单（收集用）
     */
    private void checkProperty(CredentialCheck check, List<String> violations) {
        String value = environment.getProperty(check.key());
        if (value != null && check.defaults().contains(value.trim())) {
            violations.add(check.label() + " " + check.key());
        }
    }

    /**
     * 单项检查定义。
     *
     * @param key      配置键
     * @param defaults 视为「未覆盖」的取值集合
     * @param label    面向运维的中文描述
     */
    private record CredentialCheck(String key, Set<String> defaults, String label) {
    }
}
