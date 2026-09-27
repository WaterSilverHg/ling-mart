package com.lingmart.search;

import com.lingmart.common.exception.GlobalExceptionHandler;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

/**
 * 轻营 · 检索服务启动类。
 *
 * <p>纯只读服务：直接查询 product 上架写入的 Elasticsearch 索引，无数据库依赖。</p>
 */
@SpringBootApplication(scanBasePackages = "com.lingmart.search")
@Import(GlobalExceptionHandler.class)
public class SearchApplication {

    public static void main(String[] args) {
        SpringApplication.run(SearchApplication.class, args);
    }
}
