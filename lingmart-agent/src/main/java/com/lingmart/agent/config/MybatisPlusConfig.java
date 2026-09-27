package com.lingmart.agent.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Plus 配置（分页插件）。
 *
 * <p>与其它服务保持一致（该配置类在本项目中各服务各持一份，未下沉到 lingmart-common）：
 * 缺它时 {@code selectPage} 不会拼接 LIMIT、{@code total} 恒为 0，审计查询会静默返回全表。</p>
 */
@Configuration
public class MybatisPlusConfig {

    /** 分页插件（MySQL） */
    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.MYSQL));
        return interceptor;
    }
}
