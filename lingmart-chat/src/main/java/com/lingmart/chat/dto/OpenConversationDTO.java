package com.lingmart.chat.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 发起会话请求参数。
 */
@Data
public class OpenConversationDTO {

    /** 店铺 ID */
    @NotNull(message = "店铺ID不能为空")
    private Long shopId;
}
