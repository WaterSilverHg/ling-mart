package com.lingmart.order.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 卖家发货参数（v0.27）。
 *
 * <p>发货必须先选方式：快递（1）或无需物流（2，自提 / 面交）。
 * 快递时快递公司与运单号必填；无需物流时两者必须为空。</p>
 *
 * <p>这层「字段之间的条件依赖」单靠注解表达不了（{@code @NotBlank} 无法写成「仅当 deliveryMode=1 时必填」），
 * 故只在 {@link #deliveryMode} 上做非空校验，其余交由
 * {@code OrderServiceImpl.ship} 显式校验并抛出可读的业务异常。</p>
 */
@Data
public class ShipDTO {

    /** 发货方式：1 快递 / 2 无需物流 */
    @NotNull(message = "请选择发货方式")
    private Integer deliveryMode;

    /** 快递公司（deliveryMode=1 时必填） */
    private String shippingCompany;

    /** 运单号（deliveryMode=1 时必填） */
    private String trackingNo;
}
