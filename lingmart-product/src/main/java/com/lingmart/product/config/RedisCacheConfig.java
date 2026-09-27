package com.lingmart.product.config;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.jsontype.impl.LaissezFaireSubTypeValidator;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * Redis 缓存配置（Spring Cache + RedisCacheManager）。
 *
 * <p>采用 TTL 失效（不做显式驱逐，避免多处改动）：分类/品牌列表 10 分钟、商品详情 2 分钟。
 * 生产可设环境变量覆盖 TTL。</p>
 */
@Configuration
@EnableCaching
public class RedisCacheConfig {

    /** 分类/品牌目录缓存 TTL（分钟），环境变量可覆盖 */
    private static final long CATALOG_TTL_MINUTES = 10;
    /** 商品详情缓存 TTL（分钟） */
    private static final long DETAIL_TTL_MINUTES = 2;

    /** 缓存值序列化器：注册 JavaTimeModule（否则实体含 LocalDateTime 时序列化抛异常），
     *  并激活默认类型信息（GenericJackson2JsonRedisSerializer 反序列化多态所需） */
    private GenericJackson2JsonRedisSerializer valueSerializer() {
        ObjectMapper om = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .activateDefaultTyping(LaissezFaireSubTypeValidator.instance,
                        ObjectMapper.DefaultTyping.NON_FINAL, JsonTypeInfo.As.PROPERTY);
        return new GenericJackson2JsonRedisSerializer(om);
    }

    @Bean
    public RedisCacheManager cacheManager(RedisConnectionFactory factory) {
        RedisCacheConfiguration defaults = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(Duration.ofMinutes(5))
                .serializeValuesWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(valueSerializer()))
                .disableCachingNullValues();

        Map<String, RedisCacheConfiguration> perCache = new HashMap<>();
        perCache.put("catalog", defaults.entryTtl(Duration.ofMinutes(CATALOG_TTL_MINUTES)));
        // 商品详情：不调用 disableCachingNullValues()（默认允许缓存 null），商品不存在时也短暂缓存，
        // 防缓存穿透打爆 DB；配合 @Cacheable(sync=true) 防热点击穿；短 TTL 保证新上架商品及时可见
        perCache.put("productDetail", RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(Duration.ofMinutes(DETAIL_TTL_MINUTES))
                .serializeValuesWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(valueSerializer())));

        return RedisCacheManager.builder(factory)
                .cacheDefaults(defaults)
                .withInitialCacheConfigurations(perCache)
                .build();
    }
}
