package com.lingmart.common.util;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.List;
import java.util.UUID;

/**
 * 分布式滑动窗口限流器（Redis ZSET + Lua 原子脚本）。
 *
 * <p>解决内存版限流器「多实例不共享」的问题（内存实现已删除）：基于 Redis
 * 有序集合保存窗口内请求时间戳，Lua 脚本保证「清理过期 + 计数判断 + 写入」原子执行，
 * 多实例部署共享同一窗口。语义：窗口内允许 max 次，超出拒绝。</p>
 *
 * <p>纯工具类（非 Spring Bean），调用方注入 {@link StringRedisTemplate} 后
 * {@code new RedisRateLimiter(redisTemplate)} 使用；各服务在自己的 config 包里声明
 * {@code @Bean}（auth / chat / shop 均如此）。时间戳运算在 Java 端完成后
 * 以字符串传入 Lua，绝不在脚本内做 {@code ARGV[1] - ARGV[2]} 大整数减法——
 * Redis 7 对脚本内计算出的浮点参数会直接拒绝（ERR Lua redis lib command arguments
 * must be strings or integers）。</p>
 *
 * <p>使用方注意：key 前缀 {@code lingmart:ratelimit:} 全局共享，不同业务必须用不同的 key
 * 片段（如 {@code chat-send:userId}、{@code upload:userId}、{@code sms:phone}），否则会互相挤占窗口。</p>
 */
public class RedisRateLimiter {

    /** Redis key 前缀 */
    private static final String KEY_PREFIX = "lingmart:ratelimit:";

    /**
     * 滑动窗口放行脚本。
     * <p>参数：KEYS[1]=key；ARGV[1]=当前毫秒、ARGV[2]=窗口起点毫秒（Java 已算好）、
     * ARGV[3]=成员（uuid）、ARGV[4]=窗口内最大次数、ARGV[5]=窗口时长毫秒。</p>
     */
    private static final String ALLOW_LUA = """
            redis.call('ZREMRANGEBYSCORE', KEYS[1], 0, ARGV[2])
            local count = redis.call('ZCARD', KEYS[1])
            if count >= tonumber(ARGV[4]) then
              return 0
            end
            redis.call('ZADD', KEYS[1], ARGV[1], ARGV[3])
            redis.call('PEXPIRE', KEYS[1], ARGV[5])
            return 1
            """;

    /**
     * 命中计数脚本。
     * <p>参数：KEYS[1]=key；ARGV[1]=当前毫秒、ARGV[2]=窗口起点毫秒（Java 已算好）、
     * ARGV[3]=成员（uuid）、ARGV[4]=窗口时长毫秒。</p>
     */
    private static final String HIT_LUA = """
            redis.call('ZREMRANGEBYSCORE', KEYS[1], 0, ARGV[2])
            redis.call('ZADD', KEYS[1], ARGV[1], ARGV[3])
            redis.call('PEXPIRE', KEYS[1], ARGV[4])
            return redis.call('ZCARD', KEYS[1])
            """;

    private final StringRedisTemplate redisTemplate;

    private final RedisScript<Long> allowScript = new DefaultRedisScript<>(ALLOW_LUA, Long.class);
    private final RedisScript<Long> hitScript = new DefaultRedisScript<>(HIT_LUA, Long.class);

    /**
     * @param redisTemplate Redis 字符串模板（由调用方注入）
     */
    public RedisRateLimiter(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /**
     * 滑动窗口限流：窗口内允许 max 次。
     *
     * @param key          限流键（如 chat-send:userId、upload:userId）
     * @param max          窗口内最大次数
     * @param windowMillis 窗口时长（毫秒）
     * @return true 允许通过；false 已超阈值
     */
    public boolean allow(String key, int max, long windowMillis) {
        long now = System.currentTimeMillis();
        Long result = redisTemplate.execute(allowScript,
                List.of(KEY_PREFIX + key),
                String.valueOf(now),
                String.valueOf(now - windowMillis),
                UUID.randomUUID().toString(),
                String.valueOf(max),
                String.valueOf(windowMillis));
        return result != null && result == 1L;
    }

    /**
     * 记录一次命中并返回窗口内累计次数（用于失败计数，如登录失败次数）。
     *
     * @param key          限流键
     * @param windowMillis 窗口时长（毫秒）
     * @return 窗口内累计次数
     */
    public int hit(String key, long windowMillis) {
        long now = System.currentTimeMillis();
        Long result = redisTemplate.execute(hitScript,
                List.of(KEY_PREFIX + key),
                String.valueOf(now),
                String.valueOf(now - windowMillis),
                UUID.randomUUID().toString(),
                String.valueOf(windowMillis));
        return result == null ? 0 : result.intValue();
    }

    /**
     * 清除某 key 的计数（如成功后重置）。
     *
     * @param key 限流键
     */
    public void reset(String key) {
        redisTemplate.delete(KEY_PREFIX + key);
    }
}
