package com.lingmart.auth.config;

import com.lingmart.common.util.RedisRateLimiter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 分布式限流配置：把 {@link RedisRateLimiter}（Redis ZSET + Lua 滑动窗口）注册为 Bean，
 * 供登录/验证码防刷等多实例共享场景使用。
 *
 * <p>说明：auth 原先自己有一份 {@code AuthRateLimiter}，与 common 的 {@link RedisRateLimiter}
 * 逐行重复（连 Redis key 前缀 {@code lingmart:ratelimit:} 都相同），修复时必须两处同步、易漏。
 * 已统一到 common 实现，此处仅声明 Bean（与 chat / shop 的写法一致）。</p>
 */
@Configuration
public class RateLimitConfig {

    /**
     * @param redisTemplate Redis 字符串模板（Boot 自动装配）
     * @return 分布式滑动窗口限流器
     */
    @Bean
    public RedisRateLimiter redisRateLimiter(StringRedisTemplate redisTemplate) {
        return new RedisRateLimiter(redisTemplate);
    }
}
