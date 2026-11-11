package com.lingmart.common.web;

import jakarta.servlet.Filter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 分页参数封顶的自动配置。
 *
 * <p><b>为什么限定 SERVLET</b>：common 同时被 Servlet 业务服务和 WebFlux 网关依赖。
 * 若在这里无条件注册 Servlet 过滤器，网关会因为类路径上出现 Servlet 相关 Bean 而被判定为
 * SERVLET 应用类型，直接导致 WebFlux 栈启动失败。因此用
 * {@link ConditionalOnWebApplication} 把整个配置限制在 Servlet 环境，网关侧完全不加载。</p>
 *
 * <p>开关与阈值（均可按服务覆盖）：</p>
 * <ul>
 *   <li>{@code lingmart.web.page-cap-enabled}：默认 {@code true}，置 {@code false} 关闭；</li>
 *   <li>{@code lingmart.web.max-page-size}：每页条数上限，默认 100；</li>
 *   <li>{@code lingmart.web.max-page-number}：页码上限，默认 100000。</li>
 * </ul>
 */
@AutoConfiguration
@ConditionalOnClass(OncePerRequestFilter.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(name = "lingmart.web.page-cap-enabled", havingValue = "true", matchIfMissing = true)
public class PageSizeCapAutoConfiguration {

    /**
     * 注册封顶过滤器。
     *
     * @param maxSize 每页条数上限
     * @param maxPage 页码上限
     * @return 过滤器注册对象
     */
    @Bean
    public FilterRegistrationBean<Filter> pageSizeCapFilter(
            @Value("${lingmart.web.max-page-size:100}") int maxSize,
            @Value("${lingmart.web.max-page-number:100000}") int maxPage) {
        FilterRegistrationBean<Filter> registration =
                new FilterRegistrationBean<>(new PageSizeCapFilter(maxSize, maxPage));
        // 尽量靠前：参数要在 Spring 绑定 @RequestParam 之前就被改写
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        registration.addUrlPatterns("/*");
        registration.setName("pageSizeCapFilter");
        return registration;
    }
}
