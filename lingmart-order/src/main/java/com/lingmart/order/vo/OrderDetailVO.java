package com.lingmart.order.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 订单视图（列表与详情共用，含明细）。
 */
@Data
public class OrderDetailVO {

    private Long id;

    private String orderSn;

    private Long buyerId;

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

    /** 退款拒绝原因（卖家填写） */
    private String refundRejectReason;

    /** 退款处理时间 */
    private LocalDateTime refundTime;

    private String buyerRemark;

    private LocalDateTime payTime;

    private LocalDateTime shipTime;

    private LocalDateTime completeTime;

    private LocalDateTime cancelTime;

    private LocalDateTime createTime;

    /** 订单明细 */
    private List<OrderItemVO> items;
}
