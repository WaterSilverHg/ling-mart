package com.lingmart.agent.config;

import com.lingmart.common.util.RedisRateLimiter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.client.RestClient;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;

/**
 * Agent 服务基础设施 Bean：Redis 分布式限流器（复用 lingmart-common）+ SSE 推送用的虚拟线程执行器。
 */
@Configuration
public class AgentInfraConfig {

    /** Redis ZSET + Lua 滑动窗口限流器（多实例共享计数）。 */
    @Bean
    public RedisRateLimiter redisRateLimiter(StringRedisTemplate redisTemplate) {
        return new RedisRateLimiter(redisTemplate);
    }

    /** 每个 SSE 推送任务一个虚拟线程（JDK21，阻塞 LLM 调用不占平台线程）。 */
    @Bean(destroyMethod = "shutdown")
    public ExecutorService agentSseExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }

    /**
     * SSE 并发信号量：限制同时在途的 LLM 调用，防止恶意/突发流量无限创建虚拟线程
     * 打满 LLM API 或导致 OOM。容量取 {@code lingmart.agent.max-concurrent-sse}，默认 32；
     * 超出时请求快速返回错误（见 AgentChatController）。
     */
    @Bean
    public Semaphore agentSseLimiter(AgentProperties properties) {
        return new Semaphore(Math.max(1, properties.getMaxConcurrent()), true);
    }

    /**
     * 只读工具调用业务 API 用的 RestClient，<b>baseUrl 指向网关</b>（案 B）。
     *
     * <p>为什么不直连 order 服务：走网关意味着鉴权逻辑仍然只有「网关 + 业务服务」一份，
     * Agent 只是转发者，不成为新的权限判断点。就算 Agent 被注入，它能做的也只是
     * "替用户发一个用户本来就能发的请求"。</p>
     *
     * <p>超时沿用 Spring Boot 的 {@code spring.http.client.*}（与模型调用同一套配置），
     * 避免工具调用无限期挂住自主循环的时长预算。</p>
     */
    @Bean
    public RestClient agentGatewayRestClient(RestClient.Builder builder, AgentProperties properties) {
        return builder.baseUrl(properties.getReadOnly().getGatewayBaseUrl()).build();
    }
}
