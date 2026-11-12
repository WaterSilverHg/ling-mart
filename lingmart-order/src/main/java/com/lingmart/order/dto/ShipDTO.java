package com.lingmart.order.dto;

import lombok.Data;

/**
 * 卖家发货参数。
 *
 * <p><b>v0.40 起履约方式由买家在下单时确定</b>（1 快递 / 2 到店自提），卖家发货时
 * 不再选择方式，只做两件事之一：</p>
 * <ul>
 *   <li>快递订单 → 填快递公司与运单号；</li>
 *   <li>自提订单 → 什么都不填，仅表示「备货完成，可来取货」。</li>
 * </ul>
 *
 * <p>{@link #deliveryMode} 仅为**存量订单**保留：本次升级前创建的订单没有记录履约方式，
 * 需要由请求指定一次。新订单传了就必须与订单一致，否则拒绝（防止把自提订单发成快递）。</p>
 *
 * <p>这层「字段之间的条件依赖」单靠注解表达不了（{@code @NotBlank} 无法写成「仅当是快递时必填」），
 * 故由 {@code OrderServiceImpl.ship} 显式校验并抛出可读的业务异常。</p>
 */
@Data
public class ShipDTO {

    /**
     * 履约方式：1 快递 / 2 到店自提。
     *
     * <p>仅存量订单（{@code order.delivery_mode} 为空）需要传；新订单可不传。
     * 传了则必须与订单已确定的履约方式一致。</p>
     */
    private Integer deliveryMode;

    /** 快递公司（deliveryMode=1 时必填） */
    private String shippingCompany;

    /** 运单号（deliveryMode=1 时必填） */
    private String trackingNo;
}
