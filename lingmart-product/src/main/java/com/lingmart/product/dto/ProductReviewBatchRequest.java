package com.lingmart.product.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

/**
 * 商品批量审核请求（管理员）。
 */
@Data
public class ProductReviewBatchRequest {

    /** 待审核的商品SPU ID 列表 */
    @NotEmpty(message = "请选择要审核的商品")
    private List<Long> spuIds;

    /** 是否通过 */
    @NotNull(message = "请指定审核结果")
    private Boolean approve;

    /** 拒绝原因 */
    private String rejectReason;
}
