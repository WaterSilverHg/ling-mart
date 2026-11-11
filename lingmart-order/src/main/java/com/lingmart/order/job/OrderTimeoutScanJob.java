package com.lingmart.order.job;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lingmart.order.entity.Order;
import com.lingmart.order.mapper.OrderMapper;
import com.lingmart.order.service.OrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 超时订单兜底扫描（MQ 延迟消息丢失/发送失败时的最终保障）。
 *
 * <p><b>为什么必须单独成类</b>：这个任务原先写在 {@code OrderServiceImpl} 里，
 * 由 {@code @Scheduled} 方法直接 {@code this.cancelByTimeout(...)}——**Spring 自调用**，
 * 代理拦不到，被调方法上的 {@code @Transactional} / {@code @GlobalTransactional} 全部失效。
 * 后果是「订单置已取消」与「库存解锁」不在同一个事务里：解锁失败会留下「订单已取消、
 * 库存仍锁定」且无回滚，而 MQ 那条路径（经代理调用）没有这个问题，两条路径行为不一致。</p>
 *
 * <p>拆出来后，每次取消都经注入的 {@link OrderService} 代理调用，事务语义与 MQ 路径一致。
 * 逐单 try/catch：单个订单失败不能中断整轮扫描，下一轮会再兜底。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderTimeoutScanJob {

    /** 订单状态：待支付 */
    private static final int ORDER_PENDING_PAY = 0;

    private final OrderMapper orderMapper;
    private final OrderService orderService;

    /** 支付超时时间（毫秒），与线下单/MQ 延迟队列使用同一个配置 */
    @Value("${lingmart.order.timeout-ms:120000}")
    private long timeoutMs;

    @Scheduled(fixedDelayString = "${lingmart.order.scan-interval-ms:60000}")
    public void scan() {
        List<Order> overdue = orderMapper.selectList(new LambdaQueryWrapper<Order>()
                .eq(Order::getOrderStatus, ORDER_PENDING_PAY)
                .lt(Order::getCreateTime, LocalDateTime.now().minus(Duration.ofMillis(timeoutMs))));
        int processed = 0;
        for (Order order : overdue) {
            try {
                // 关键：经代理调用（不能写成 this.cancelByTimeout）
                orderService.cancelByTimeout(order.getOrderSn());
                processed++;
            } catch (Exception e) {
                log.error("超时兜底取消失败：orderSn={}, err={}", order.getOrderSn(), e.getMessage());
            }
        }
        if (processed > 0) {
            log.info("超时兜底扫描：本轮处理 {} 个订单", processed);
        }
    }
}
