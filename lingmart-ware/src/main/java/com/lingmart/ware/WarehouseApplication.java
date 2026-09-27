package com.lingmart.ware;

import com.lingmart.common.exception.GlobalExceptionHandler;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 轻营 · 仓储服务启动类。
 *
 * <p>库存锁定/扣减/解锁由订单服务在 Seata 全局事务内调用，本服务仅需作为 RM
 * 参与分布式事务，不主动发起。另含库存流水定时补偿任务（见 job.StockFlowCompensationJob）。</p>
 */
@SpringBootApplication(scanBasePackages = "com.lingmart.ware")
@MapperScan("com.lingmart.ware.mapper")
@EnableFeignClients(basePackages = "com.lingmart.ware.feign")
@Import(GlobalExceptionHandler.class)
@EnableScheduling
public class WarehouseApplication {

    public static void main(String[] args) {
        SpringApplication.run(WarehouseApplication.class, args);
    }
}
