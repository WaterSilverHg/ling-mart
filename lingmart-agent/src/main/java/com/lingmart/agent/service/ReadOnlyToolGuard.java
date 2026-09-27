package com.lingmart.agent.service;

import com.lingmart.agent.config.AgentProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.DigestUtils;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * 只读工具的两道共用闸门：**会话内短缓存** 与 **会话配额**。
 *
 * <p>放在一个类里是因为它们服务于同一个目的——只读工具"免用户确认"之后，
 * 模型可以在一次提问里连续调用多次，必须防止它变成"无界请求放大器"：
 * 一个人问一句，可能触发 3 次订单查询，被诱导时可能触发几十次。</p>
 *
 * <p><b>缓存 Key 必须含 userId</b>：这是本类最容易写错、后果最严重的一行——
 * 漏掉 userId 就是"用户 A 看到用户 B 的订单"。缓存值本身是已经过白名单裁剪的成品文本，
 * 直接复用即可。</p>
 */
@Slf4j
@Service
public class ReadOnlyToolGuard {

    private static final String CACHE_PREFIX = "agent:toolcache:";
    private static final String QUOTA_PREFIX = "agent:toolquota:";

    private final StringRedisTemplate redis;
    private final AgentProperties properties;

    public ReadOnlyToolGuard(StringRedisTemplate redis, AgentProperties properties) {
        this.redis = redis;
        this.properties = properties;
    }

    /**
     * 取会话内短缓存。
     *
     * @param paramsKey 调用参数的紧凑表示（参与哈希，保证"同参数才复用"）
     * @return 命中则返回上次的结果文本
     */
    public String cached(Long userId, String sessionId, String toolName, String paramsKey) {
        try {
            return redis.opsForValue().get(cacheKey(userId, sessionId, toolName, paramsKey));
        } catch (Exception e) {
            log.warn("读只读工具缓存失败：{}", e.getMessage());
            return null;
        }
    }

    /** 写入会话内短缓存。TTL ≤0 表示不缓存。 */
    public void cache(Long userId, String sessionId, String toolName, String paramsKey, String value) {
        int ttl = properties.getReadOnly().getCacheTtlSeconds();
        if (ttl <= 0 || value == null) {
            return;
        }
        try {
            redis.opsForValue().set(cacheKey(userId, sessionId, toolName, paramsKey), value,
                    ttl, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("写只读工具缓存失败：{}", e.getMessage());
        }
    }

    /**
     * 消耗一次会话配额。
     *
     * @return true=允许执行；false=已达会话上限（调用方应直接返回拒绝提示，不再发请求）
     */
    public boolean tryConsumeQuota(Long userId, String sessionId) {
        int max = Math.max(1, properties.getReadOnly().getMaxCallsPerSession());
        try {
            String key = QUOTA_PREFIX + userId + ":" + sessionId;
            Long used = redis.opsForValue().increment(key);
            redis.expire(key, properties.getMemory().getTtlHours(), TimeUnit.HOURS);
            return used != null && used <= max;
        } catch (Exception e) {
            // Redis 不可用时按"允许"处理：配额是防滥用的兜底，不该因它不可用而让只读工具整体失效
            log.warn("只读工具配额检查失败，本次放行：{}", e.getMessage());
            return true;
        }
    }

    /** 当前会话已用的配额（诊断用）。 */
    public long quotaUsed(Long userId, String sessionId) {
        try {
            String v = redis.opsForValue().get(QUOTA_PREFIX + userId + ":" + sessionId);
            return v == null ? 0 : Long.parseLong(v);
        } catch (Exception e) {
            return 0;
        }
    }

    private String cacheKey(Long userId, String sessionId, String toolName, String paramsKey) {
        String hash = DigestUtils.md5DigestAsHex(
                (sessionId + "|" + paramsKey).getBytes(StandardCharsets.UTF_8));
        return CACHE_PREFIX + userId + ":" + toolName + ":" + hash;
    }
}
