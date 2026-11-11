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

    /**
     * 退款申请时间（买家提交）。
     *
     * <p>与 {@link #refundTime}（处理时间）是两回事：本字段是「卖家超时未处理则自动同意」的计时锚点。
     * 存量订单该字段为 null，因此**不会被自动同意**——这是刻意的安全默认，
     * 避免升级后把历史积压的退款申请一次性全退掉。</p>
     */
    private LocalDateTime refundApplyTime;

    /** 买家备注 */
    private String buyerRemark;

    /** 幂等键（客户端 Idempotency-Key 头，防重复下单） */
    private String idempotencyKey;

    private LocalDateTime payTime;

    private LocalDateTime shipTime;

    /**
     * 发货方式：1 快递 / 2 无需物流（自提、面交）。
     *
     * <p>null 表示尚未发货；本次升级（v0.27）之前已发货的存量订单也是 null，
     * 前端对这种情况回退显示「已发货」。</p>
     */
    private Integer deliveryMode;

    /** 快递公司（仅 deliveryMode=1 时有值） */
    private String shippingCompany;

    /** 运单号（仅 deliveryMode=1 时有值） */
    private String trackingNo;

    /**
     * 下单时选用的地址簿 ID（仅留痕，不参与查询）。
     *
     * <p>刻意不做外键：地址簿改动/删除不能影响已生成的订单，
     * 后面的 {@code receiver*} 四列才是订单实际使用的收货信息。</p>
     */
    private Long addressId;

    /** 收货人姓名（下单快照） */
    private String receiverName;

    /** 收货人手机号（下单快照） */
    private String receiverPhone;

    /** 省市区（下单快照，如「浙江省 杭州市 西湖区」） */
    private String receiverRegion;

    /** 详细地址（下单快照） */
    private String receiverDetail;

    private LocalDateTime completeTime;

    private LocalDateTime cancelTime;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
