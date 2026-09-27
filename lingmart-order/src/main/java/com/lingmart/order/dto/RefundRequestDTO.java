package com.lingmart.order.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 买家申请退款参数。
 */
@Data
public class RefundRequestDTO {

    /** 退款原因 */
    @NotBlank(message = "请填写退款原因")
    private String reason;
}
