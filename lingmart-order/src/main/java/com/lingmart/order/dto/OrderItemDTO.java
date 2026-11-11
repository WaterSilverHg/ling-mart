package com.lingmart.order.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 下单商品条目：仅需 SKU ID 与数量，价格/标题由服务端经 Feign 从商品服务取，防篡改。
 */
@Data
public class OrderItemDTO {

    /** 商品SKU ID */
    @NotNull(message = "SKU 不能为空")
    private Long skuId;

    /** 购买数量（上限 999：防单行数量溢出与「一件商品下单一亿份」式的脏数据） */
    @NotNull(message = "数量不能为空")
    @Min(value = 1, message = "数量至少为 1")
    @Max(value = 999, message = "单件商品数量不能超过 999")
    private Integer num;
}
