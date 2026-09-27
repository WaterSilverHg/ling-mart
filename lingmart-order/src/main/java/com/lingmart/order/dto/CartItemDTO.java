package com.lingmart.order.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 加入购物车请求。
 */
@Data
public class CartItemDTO {

    /** 商品SKU ID */
    @NotNull(message = "SKU 不能为空")
    private Long skuId;

    /** 数量 */
    @NotNull(message = "数量不能为空")
    @Min(value = 1, message = "数量至少为 1")
    private Integer num;
}
