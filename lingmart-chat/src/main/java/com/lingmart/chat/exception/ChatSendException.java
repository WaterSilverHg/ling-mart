package com.lingmart.chat.exception;

import com.lingmart.chat.dto.SendMessageDTO;
import lombok.Getter;

/**
 * 聊天消息发送失败（STOMP 应用层异常）。
 *
 * <p>把 {@code /app/chat/send} 处理过程中的异常统一包装成本异常，交由
 * {@code ChatController#onSendFailed} 回投到发送者专属队列 {@code /user/queue/errors}，
 * 而不再向上抛出让 Spring 回 STOMP ERROR 帧（ERROR 帧会导致连接被关闭、前端出现
 * 5 秒重连空窗，且无法定位是哪条消息失败）。</p>
 *
 * <p>携带原始请求是为了让前端能凭 {@code clientMsgId} 精确定位到「发送失败」的气泡，
 * 并用 {@code content} 支持一键重发，避免用户输入的文本丢失。</p>
 */
@Getter
public class ChatSendException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** 原始发送请求（可能为 null：异常发生在参数解析阶段、拿不到原始负载时） */
    private final SendMessageDTO dto;

    /** 业务错误码（沿用 BizException 的错误码；非业务异常时为通用业务错误码） */
    private final Integer code;

    /**
     * @param dto    原始发送请求（可空）
     * @param code   业务错误码
     * @param reason 失败原因（用户可见文案）
     */
    public ChatSendException(SendMessageDTO dto, Integer code, String reason) {
        super(reason);
        this.dto = dto;
        this.code = code;
    }
}
