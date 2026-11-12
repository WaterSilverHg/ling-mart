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

    /** 退款申请时间（买家提交；卖家超时未处理将自动同意） */
    private LocalDateTime refundApplyTime;

    private String buyerRemark;

    private LocalDateTime payTime;

    private LocalDateTime shipTime;

    /** 履约方式：1 快递 / 2 到店自提（下单时确定）；null = v0.40 之前的存量订单 */
    private Integer deliveryMode;

    /** 快递公司（仅 deliveryMode=1 时有值） */
    private String shippingCompany;

    /** 运单号（仅 deliveryMode=1 时有值） */
    private String trackingNo;

    /** 收货人姓名（下单快照；v0.39 之前的存量订单为空） */
    private String receiverName;

    /** 收货人手机号（下单快照） */
    private String receiverPhone;

    /** 省市区（下单快照） */
    private String receiverRegion;

    /** 详细地址（下单快照） */
    private String receiverDetail;

    /** 完整收货地址（省市区 + 详细地址，展示用；无收货信息时为 null） */
    private String receiverFullAddress;

    /** 自提门店名称（下单快照，仅到店自提订单有值） */
    private String pickupShopName;

    /** 自提门店地址（下单快照） */
    private String pickupAddress;

    /** 自提门店电话（下单快照） */
    private String pickupPhone;

    private LocalDateTime completeTime;

    private LocalDateTime cancelTime;

    private LocalDateTime createTime;

    /** 订单明细 */
    private List<OrderItemVO> items;
}
