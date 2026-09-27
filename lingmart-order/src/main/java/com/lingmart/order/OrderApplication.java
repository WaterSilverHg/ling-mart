package com.lingmart.order;

import com.lingmart.common.exception.GlobalExceptionHandler;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 轻营 · 订单服务启动类。
 *
 * <p>下单流程发起 Seata 全局事务（建单 + 锁库存），并基于 RabbitMQ TTL+死信实现
 * 支付超时自动取消；另开启定时扫描兜底（MQ 消息丢失时也能取消超时订单）。</p>
 */
@SpringBootApplication(scanBasePackages = "com.lingmart.order")
@MapperScan("com.lingmart.order.mapper")
@EnableFeignClients(basePackages = "com.lingmart.order.feign")
@EnableScheduling
@Import(GlobalExceptionHandler.class)
public class OrderApplication {

    public static void main(String[] args) {
        SpringApplication.run(OrderApplication.class, args);
    }
}
