package com.lingmart.order.dto;

import lombok.Data;

/**
 * 卖家审核退款参数。
 */
@Data
public class RefundReviewDTO {

    /** 是否同意退款 */
    private Boolean approve;

    /** 拒绝原因（拒绝时必填） */
    private String rejectReason;
}
