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
 * 退款申请超时自动同意（v0.27）。
 *
 * <p>买家提交退款申请后，卖家超过 {@code lingmart.order.refund-auto-approve-ms}（默认 24 小时）
 * 未处理，系统自动按「同意退款」处理，避免买家被无限期挂着。</p>
 *
 * <h3>为什么单独一个 Bean，而不是写在 OrderServiceImpl 里</h3>
 * <p>订单服务里已有的 {@code scanTimeoutOrders} 是 {@code @Scheduled} 方法，
 * 它在同类内部直接 {@code this.cancelByTimeout(...)} —— 这是 **Spring 自调用**，
 * 被调方法上的 {@code @Transactional} / {@code @GlobalTransactional} **不会生效**
 * （代理无法拦截 this 调用）。本任务每单都要写订单表并跨服务回补库存、回退销量，
 * 事务是必需的，因此把扫描入口放在独立 Bean 中、经注入的 {@code OrderService} 代理逐笔调用。
 * （仓库里 ware 的 {@code StockFlowCompensationJob} 也是同样的结构。）</p>
 *
 * <h3>为什么只定时扫描，不复用订单超时那套 MQ 延迟队列</h3>
 * <p>订单超时用的是「TTL 延迟队列 + 定时扫描兜底」，因为它的时间窗只有 2 分钟，
 * 需要尽量准时。退款是 24 小时的窗口——一分钟一轮的扫描意味着实际生效时间落在
 * 24h ~ 24h+1min，对业务毫无差别，却要多出一整套队列、死信与消费者配置。</p>
 *
 * <h3>安全默认</h3>
 * <p>只扫 {@code refund_apply_time} 非空的记录。本次升级（v0.27）之前申请中的存量订单该列为
 * null，因此**永远不会被自动同意**，仍需卖家手工审核——刻意为之，
 * 避免升级上线后把历史积压的退款申请一次性全退掉。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RefundAutoApproveJob {

    /**
     * 单轮扫描上限：每个订单对应一个 Seata 全局事务，不限量会在积压时一次拉起成百上千个全局事务打满 TC。
     * （订单的超时扫描没加这个限制，是个已知待优化项；新任务不再重复这个问题。）
     */
    private static final int BATCH_LIMIT = 100;

    /** 退款状态「退款中」（与 {@code OrderServiceImpl.REFUND_PENDING} 一致，后者为私有常量） */
    private static final int REFUND_PENDING = 1;

    private final OrderMapper orderMapper;
    private final OrderService orderService;

    /** 申请后多久未处理则自动同意（毫秒），默认 24 小时 */
    @Value("${lingmart.order.refund-auto-approve-ms:86400000}")
    private long refundAutoApproveMs;

    @Scheduled(fixedDelayString = "${lingmart.order.refund-scan-interval-ms:60000}")
    public void scanAndAutoApprove() {
        LocalDateTime deadline = LocalDateTime.now().minus(Duration.ofMillis(refundAutoApproveMs));
        List<Order> overdue = orderMapper.selectList(new LambdaQueryWrapper<Order>()
                .eq(Order::getRefundStatus, REFUND_PENDING)
                // 存量订单该列为 null，不参与自动同意（见类注释「安全默认」）
                .isNotNull(Order::getRefundApplyTime)
                .lt(Order::getRefundApplyTime, deadline)
                .orderByAsc(Order::getRefundApplyTime)
                .last("LIMIT " + BATCH_LIMIT));
        if (overdue.isEmpty()) {
            return;
        }
        int approved = 0;
        for (Order order : overdue) {
            try {
                // 经代理调用，事务注解才生效
                orderService.autoApproveTimeoutRefund(order.getOrderSn());
                approved++;
            } catch (Exception e) {
                // 单笔失败不影响本轮其余订单（与订单超时扫描同一策略）
                log.error("退款超时自动同意失败：orderSn={}, err={}", order.getOrderSn(), e.getMessage());
            }
        }
        log.info("退款超时自动同意：本轮命中 {} 单，成功 {} 单（等待阈值 {} 小时）",
                overdue.size(), approved, refundAutoApproveMs / 3_600_000);
    }
}
