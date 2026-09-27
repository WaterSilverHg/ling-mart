package com.lingmart.product.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 商品审核请求（管理员）。
 */
@Data
public class ProductReviewRequest {

    /** 是否通过 */
    @NotNull(message = "请指定审核结果")
    private Boolean approve;

    /** 拒绝原因 */
    private String rejectReason;
}
