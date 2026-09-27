package com.lingmart.chat.config;

import com.lingmart.common.util.RedisRateLimiter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 分布式限流配置：把 {@link RedisRateLimiter}（Redis ZSET + Lua 滑动窗口）注册为 Bean，
 * 供发消息防刷屏等多实例共享场景使用。
 */
@Configuration
public class RateLimitConfig {

    /**
     * 发消息限流器（多实例共享同一 Redis 窗口）。
     *
     * @param redisTemplate Redis 字符串模板
     * @return 分布式滑动窗口限流器
     */
    @Bean
    public RedisRateLimiter redisRateLimiter(StringRedisTemplate redisTemplate) {
        return new RedisRateLimiter(redisTemplate);
    }
}
