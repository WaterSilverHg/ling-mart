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
 * 发货超时自动确认收货（v0.39）。
 *
 * <p><b>为什么需要它</b>：订单进入「已发货」后，只有买家手动点「确认收货」才会流转到「已完成」。
 * 买家不点，订单就永远停在状态 2——完成时间、卖家经营统计（只统计 {@code IN(1,2,3)} 里
 * 已完成部分的口径）、以及「这笔交易到底结束没有」都失去依据。真实电商里这是必须存在的兜底。</p>
 *
 * <p><b>与买家手动确认的关系</b>：两者共用 {@link OrderService#confirmBySystem} 里的同一个原子守卫
 * （{@code WHERE order_status = 2}）。谁先命中谁生效，另一方静默跳过，不会重复写完成时间。</p>
 *
 * <p><b>为什么每轮加 LIMIT</b>：长期停机后可能积压大量已发货订单，一轮把全部订单拉进内存
 * 再逐个开事务，会重演「超时扫描没有 LIMIT」的老问题（见 todo-and-optimization 第六节 #6）。
 * 每轮最多处理 {@link #BATCH_SIZE} 笔，剩余的下轮继续，进度可见、内存可控。</p>
 *
 * <p><b>多实例</b>：与项目其它定时任务一样没有分布式租约，依赖状态守卫保证幂等——
 * 多个实例同时扫描只会浪费查询，不会产生错误的二次流转。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderAutoConfirmJob {

    /** 订单状态：已发货（与 OrderServiceImpl 中的常量保持一致） */
    private static final int ORDER_SHIPPED = 2;

    /** 单轮最多处理的订单数 */
    private static final int BATCH_SIZE = 200;

    private final OrderMapper orderMapper;
    private final OrderService orderService;

    /** 发货后多少天自动确认收货（默认 7 天，与主流电商一致） */
    @Value("${lingmart.order.auto-confirm-days:7}")
    private long autoConfirmDays;

    /**
     * 扫描并逐单自动确认。
     *
     * <p>经注入的 {@link OrderService} 代理调用，保证 {@code @Transactional} 生效
     * （不能写成 {@code this.xxx} —— v0.37 修过同一类自调用问题）。</p>
     */
    @Scheduled(fixedDelayString = "${lingmart.order.auto-confirm-scan-ms:3600000}")
    public void scan() {
        LocalDateTime threshold = LocalDateTime.now().minus(Duration.ofDays(autoConfirmDays));
        List<Order> overdue = orderMapper.selectList(new LambdaQueryWrapper<Order>()
                .eq(Order::getOrderStatus, ORDER_SHIPPED)
                .lt(Order::getShipTime, threshold)
                .orderByAsc(Order::getShipTime)
                .last("LIMIT " + BATCH_SIZE));
        if (overdue.isEmpty()) {
            return;
        }
        int processed = 0;
        for (Order order : overdue) {
            try {
                orderService.confirmBySystem(order.getOrderSn());
                processed++;
            } catch (Exception e) {
                // 单笔失败不影响其它订单，下轮会重试同一批（仍是幂等的）
                log.error("自动确认收货失败：orderSn={}, err={}", order.getOrderSn(), e.getMessage());
            }
        }
        log.info("自动确认收货扫描：阈值 {} 天，本轮处理 {} 单", autoConfirmDays, processed);
    }
}
