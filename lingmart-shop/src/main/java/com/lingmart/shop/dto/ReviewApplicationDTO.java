package com.lingmart.shop.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 入驻申请审核请求参数。
 */
@Data
public class ReviewApplicationDTO {

    /** 是否通过：true 通过，false 拒绝 */
    @NotNull(message = "请指定审核结果")
    private Boolean approve;

    /** 拒绝原因（拒绝时必填） */
    private String rejectReason;
}
