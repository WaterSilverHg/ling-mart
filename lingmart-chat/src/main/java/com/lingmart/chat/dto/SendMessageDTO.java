package com.lingmart.chat.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 发送消息请求参数（WebSocket STOMP 应用层消息）。
 */
@Data
public class SendMessageDTO {

    /** 会话 ID */
    @NotNull(message = "会话ID不能为空")
    private Long conversationId;

    /** 消息内容 */
    @NotBlank(message = "消息内容不能为空")
    @Size(max = 1000, message = "消息最长 1000 个字符")
    private String content;

    /**
     * 客户端消息 ID（前端生成，如 UUID；可空）。
     *
     * <p>用于「乐观渲染 + 发送失败回执」的关联：服务端广播时原样回显，
     * 前端据此把「发送中」气泡替换为正式消息；发送失败时同 ID 回投到
     * {@code /user/queue/errors}，前端定位到具体气泡并支持一键重发。</p>
     */
    @Size(max = 64, message = "客户端消息ID最长 64 个字符")
    private String clientMsgId;
}
