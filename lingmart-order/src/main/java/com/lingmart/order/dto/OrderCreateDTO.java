package com.lingmart.order.dto;

import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

/**
 * 下单请求：单店铺订单（前端按店铺分组结算）。
 */
@Data
public class OrderCreateDTO {

    /** 订单商品条目（同属一个店铺） */
    @NotEmpty(message = "订单商品不能为空")
    private List<OrderItemDTO> items;

    /** 买家备注 */
    private String buyerRemark;
}
