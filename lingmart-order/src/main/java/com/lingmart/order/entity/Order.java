package com.lingmart.order.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 订单主表实体。
 *
 * <p>order_status：0待支付 1已支付 2已发货 3已完成 4已取消 5已退款；
 * refund_status：0无退款 1退款中 2已退款 3退款拒绝。</p>
 */
@Data
@TableName("`order`")
public class Order {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 订单号（唯一） */
    private String orderSn;

    /** 买家用户ID */
    private Long buyerId;

    /** 所属店铺ID */
    private Long shopId;

    /** 订单总金额 */
    private BigDecimal totalAmount;

    /** 支付状态：0未支付 1已支付 */
    private Integer payStatus;

    /** 订单状态：0待支付 1已支付 2已发货 3已完成 4已取消 5已退款 */
    private Integer orderStatus;

    /** 退款状态：0无退款 1退款中 2已退款 3退款拒绝 */
    private Integer refundStatus;

    /** 退款申请原因（买家填写） */
    private String refundReason;

    /** 退款拒绝原因（卖家填写，仅拒绝时有值） */
    private String refundRejectReason;

    /** 退款处理时间（通过/拒绝） */
    private LocalDateTime refundTime;

    /** 买家备注 */
    private String buyerRemark;

    /** 幂等键（客户端 Idempotency-Key 头，防重复下单） */
    private String idempotencyKey;

    private LocalDateTime payTime;

    private LocalDateTime shipTime;

    private LocalDateTime completeTime;

    private LocalDateTime cancelTime;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
