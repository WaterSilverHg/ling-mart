package com.lingmart.order.service;

import com.lingmart.common.result.PageResult;
import com.lingmart.order.dto.OrderCreateDTO;
import com.lingmart.order.dto.OrderExportRow;
import com.lingmart.order.vo.OrderDetailVO;
import com.lingmart.order.vo.SellerStatsVO;

import java.util.function.Consumer;

/**
 * 订单服务业务接口。
 *
 * <p>下单为 Seata 全局事务（建单 + 锁库存，任一失败整体回滚）；支付/取消/发货为本地事务 +
 * Feign 同步库存动作；超时取消由 RabbitMQ 消费者触发。</p>
 */
public interface OrderService {

    /**
     * 创建订单（Seata 全局事务内：建单 + 锁库存；支持幂等，相同幂等键只建一单）。
     *
     * @param buyerId        买家用户ID
     * @param idempotencyKey 幂等键（客户端 Idempotency-Key 头，可为 null；重复则返回已有订单号）
     * @param dto            下单参数
     * @return 订单号
     */
    String createOrder(Long buyerId, String idempotencyKey, OrderCreateDTO dto);

    /**
     * 发送支付超时消息（下单成功、全局事务提交后调用）。
     *
     * @param orderSn 订单号
     */
    void sendTimeoutMessage(String orderSn);

    /**
     * 模拟支付：置已支付 + 扣减库存。
     *
     * @param buyerId 买家用户ID
     * @param orderSn 订单号
     */
    void pay(Long buyerId, String orderSn);

    /**
     * 取消订单：置已取消 + 解锁库存。
     *
     * @param buyerId 买家用户ID
     * @param orderSn 订单号
     */
    void cancel(Long buyerId, String orderSn);

    /**
     * 卖家发货：置已发货。
     *
     * @param userId  卖家用户ID
     * @param orderSn 订单号
     */
    void ship(Long userId, String orderSn);

    /**
     * 买家确认收货：置已完成。
     *
     * @param buyerId 买家用户ID
     * @param orderSn 订单号
     */
    void confirm(Long buyerId, String orderSn);

    /**
     * 买家申请退款：已支付/已发货/已完成的订单、且当前无退款记录时可申请。
     *
     * @param buyerId 买家用户ID
     * @param orderSn 订单号
     * @param reason  退款原因
     */
    void requestRefund(Long buyerId, String orderSn, String reason);

    /**
     * 卖家审核退款：通过则模拟退款（订单置已退款 + 库存回补），拒绝则置退款拒绝。
     *
     * @param userId       卖家用户ID
     * @param orderSn      订单号
     * @param approve      是否同意退款
     * @param rejectReason 拒绝原因（拒绝时必填）
     */
    void reviewRefund(Long userId, String orderSn, boolean approve, String rejectReason);

    /**
     * 支付超时自动取消（RabbitMQ 消费者调用，无买家上下文）。
     *
     * @param orderSn 订单号
     */
    void cancelByTimeout(String orderSn);

    /**
     * 买家订单列表（分页）。
     *
     * @param buyerId 买家用户ID
     * @param page    页码（从 1 起）
     * @param size    每页大小
     * @return 分页订单视图
     */
    /**
     * 买家订单列表（分页）。
     *
     * @param orderStatus 可选，按订单状态过滤（0待支付/1已支付/2已发货/3已完成/4已取消/5已退款）；
     *                    传 null 表示不过滤。加这个参数是为了让「我有几个待支付订单」这类问题
     *                    能用 {@code total} 精确回答——此前 total 是"全部订单数"，按状态数列表既不准又会漏。
     */
    PageResult<OrderDetailVO> buyerOrders(Long buyerId, long page, long size, Integer orderStatus);

    /**
     * 卖家订单列表（按当前用户店铺过滤，分页）。
     *
     * @param userId 卖家用户ID
     * @param page   页码（从 1 起）
     * @param size   每页大小
     * @return 分页订单视图
     */
    PageResult<OrderDetailVO> sellerOrders(Long userId, long page, long size);

    /**
     * 订单详情（买家或对应店铺卖家可见）。
     *
     * @param userId  当前用户ID
     * @param orderSn 订单号
     * @return 订单视图
     */
    OrderDetailVO detail(Long userId, String orderSn);

    /**
     * 卖家经营数据统计（概览 / 状态分布 / 销售趋势 / 热销 TOP5）。
     *
     * @param userId 卖家用户ID
     * @param range  统计范围：7d / 30d / all
     * @return 统计视图（未开店返回全零）
     */
    SellerStatsVO sellerStats(Long userId, String range);

    /**
     * 卖家订单导出（流式）：DB 游标逐行读取订单明细，经 {@code rowConsumer} 逐行写出。
     *
     * <p>大订单量下避免全量加载进内存（当前端按行写入 Excel 时，内存占用恒定）。</p>
     *
     * @param userId      卖家用户ID
     * @param days        导出近 N 天（&le; 0 表示全部）
     * @param rowConsumer 每行回调（含中文状态文本，行内可写出）
     */
    void streamSellerOrders(Long userId, int days, Consumer<OrderExportRow> rowConsumer);

    /**
     * 买家订单导出（流式）：同 {@link #streamSellerOrders}，按买家维度游标逐行读取。
     *
     * @param buyerId     买家用户ID
     * @param days        导出近 N 天（&le; 0 表示全部）
     * @param rowConsumer 每行回调（含中文状态文本）
     */
    void streamBuyerOrders(Long buyerId, int days, Consumer<OrderExportRow> rowConsumer);
}
