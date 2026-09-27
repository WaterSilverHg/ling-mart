package com.lingmart.shop.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 静态资源配置：对外提供上传图片访问。
 *
 * <p>上传接口把图片写入 {@code lingmart.upload.dir}，本配置把 {@code /uploads/**}
 * 映射到该目录，经网关静态路由（/uploads/** → lb://lingmart-shop）对外可访问。</p>
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    /** 上传根目录（与 UploadController 保持一致） */
    @Value("${lingmart.upload.dir:./uploads}")
    private String uploadDir;

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        String location = uploadDir.endsWith("/") || uploadDir.endsWith("\\") ? uploadDir : uploadDir + "/";
        registry.addResourceHandler("/uploads/**")
                .addResourceLocations("file:" + location);
    }
}
