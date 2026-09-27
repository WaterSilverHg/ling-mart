package com.lingmart.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lingmart.order.entity.OrderPaidEvent;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 支付成功 Outbox Mapper。
 *
 * <p>状态机：{@code 0 待发送 → 2 发送中 → 1 已发送}。先 CAS 抢占为 2 再发布，是「至多一个轮询
 * 在发同一条事件」的保证；抢占后崩溃/异常由 {@code resetStuckSending} 或发布异常分支回置 0 重发。
 * 表列类型 TINYINT，新增语义 2 无需 DDL（仅列注释更新，见 sql/upgrade 脚本）。</p>
 */
public interface OrderPaidEventMapper extends BaseMapper<OrderPaidEvent> {

    /**
     * 抢占发送权：0待发送 → 2发送中（条件更新）。
     *
     * @param id 事件主键
     * @return 影响行数（0=已被其他轮询抢占或状态已变，跳过）
     */
    @Update("UPDATE order_paid_event SET status = 2, update_time = NOW() " +
            "WHERE id = #{id} AND status = 0")
    int claimSending(@Param("id") Long id);

    /**
     * 置为“已发送”：2发送中 → 1已发送（必须先 claimSending 成功）。
     *
     * @param id 事件主键
     * @return 影响行数（0=状态已变化，如已被卡死回收为待发送）
     */
    @Update("UPDATE order_paid_event SET status = 1, update_time = NOW() " +
            "WHERE id = #{id} AND status = 2")
    int markSent(@Param("id") Long id);

    /**
     * 发布异常后立即回置：2发送中 → 0待发送，让下一轮直接重试（不必等卡死回收超时）。
     *
     * @param id 事件主键
     * @return 影响行数
     */
    @Update("UPDATE order_paid_event SET status = 0, update_time = NOW() " +
            "WHERE id = #{id} AND status = 2")
    int resetToPending(@Param("id") Long id);

    /**
     * 回收卡死事件：抢占后进程中断会留下 2发送中 的孤儿，回置 0 让下一轮重发。
     *
     * @param stuckMinutes 超过该分钟数的 2 视为孤儿
     * @return 回收条数
     */
    @Update("UPDATE order_paid_event SET status = 0, update_time = NOW() " +
            "WHERE status = 2 AND update_time < DATE_SUB(NOW(), INTERVAL #{stuckMinutes} MINUTE)")
    int resetStuckSending(@Param("stuckMinutes") int stuckMinutes);
}
