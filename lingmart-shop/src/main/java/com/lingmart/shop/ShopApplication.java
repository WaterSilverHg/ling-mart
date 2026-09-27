package com.lingmart.shop;

import com.lingmart.common.exception.GlobalExceptionHandler;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 轻营 · 店铺服务启动类。
 *
 * <p>负责商家入驻申请、店铺信息管理、店铺状态审核。
 * 通过 Feign 调用 lingmart-auth 提升审核通过用户的角色为卖家（失败由定时任务补偿）。</p>
 */
@SpringBootApplication(scanBasePackages = "com.lingmart.shop")
@MapperScan("com.lingmart.shop.mapper")
@EnableFeignClients(basePackages = "com.lingmart.shop.feign")
@EnableScheduling
@Import(GlobalExceptionHandler.class)
public class ShopApplication {

    public static void main(String[] args) {
        SpringApplication.run(ShopApplication.class, args);
    }
}
