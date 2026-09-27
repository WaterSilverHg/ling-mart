package com.lingmart.agent;

import com.lingmart.common.exception.GlobalExceptionHandler;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 轻营 · 网页浮窗 Agent(Copilot) 服务启动类。
 *
 * <p>基于 Spring AI 提供知识库问答与页面操作引导：经网关 {@code /api/agent/**} 接入，
 * SSE 流式下发文字与「操作意图」，由前端浮窗展示并征求用户确认后执行（服务不代操作）。
 * 会话记忆存 Redis，身份由本服务<b>自行验签 JWT</b> 得到（见 {@code JwtAuthService}），
 * 不信任网关透传的 {@code X-User-Id}。</p>
 *
 * <p>唯一落库的数据是「操作审计日志」（{@code lingmart_agent} 库），用于事后追溯；
 * 该库不可用不影响对话（见 {@code AgentAuditService} 的失败容忍策略），
 * 以便 Agent 保持"可选组件、不拖垮主链路"的定位。</p>
 */
@SpringBootApplication(scanBasePackages = "com.lingmart.agent")
@ConfigurationPropertiesScan(basePackages = "com.lingmart.agent.config")
@MapperScan("com.lingmart.agent.mapper")
@EnableScheduling
@Import(GlobalExceptionHandler.class)
public class AgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(AgentApplication.class, args);
    }
}
