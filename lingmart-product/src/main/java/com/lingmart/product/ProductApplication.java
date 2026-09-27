package com.lingmart.product;

import com.lingmart.common.exception.GlobalExceptionHandler;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 轻营 · 商品服务启动类。
 *
 * <p>负责商品分类/品牌、SPU/SKU 管理、商品上下架，并在上架时同步写入
 * Elasticsearch。通过 Feign 调用 lingmart-shop 获取当前用户的店铺（数据隔离）。</p>
 */
@SpringBootApplication(scanBasePackages = "com.lingmart.product")
@MapperScan("com.lingmart.product.mapper")
@EnableFeignClients(basePackages = "com.lingmart.product.feign")
@EnableScheduling
@EnableAsync
@Import(GlobalExceptionHandler.class)
public class ProductApplication {

    public static void main(String[] args) {
        SpringApplication.run(ProductApplication.class, args);
    }
}
