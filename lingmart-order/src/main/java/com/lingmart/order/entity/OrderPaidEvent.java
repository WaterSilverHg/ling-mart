package com.lingmart.order.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 订单支付成功 Outbox 事件实体（order_paid_event）。
 *
 * <p>支付成功事务内与订单状态同库写入 status=0 待发记录；提交后由
 * OrderPaidOutboxRelay 定时发布到 MQ 并置 status=1。event_id 全局唯一，
 * 配合下游（ware）幂等扣减，Outbox 重发不会重复扣。</p>
 */
@Data
@TableName("order_paid_event")
public class OrderPaidEvent {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 事件唯一 ID（UUID） */
    private String eventId;

    /** 订单号 */
    private String orderSn;

    /** 事件载荷（OrderPaidMessage JSON） */
    private String payload;

    /** 0=待发送 1=已发送 */
    private Integer status;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
