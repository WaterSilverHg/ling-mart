package com.lingmart.chat.vo;

import lombok.Data;

/**
 * 消息发送失败回执（投递到发送者的 {@code /user/queue/errors}）。
 *
 * <p>前端凭 {@link #clientMsgId} 把对应的「发送中」气泡标记为失败，并可
 * 直接用 {@link #content} 一键重发 —— 解决旧实现「发送被限流/被拒后文本已被清空、
 * 界面无任何提示」的问题。</p>
 */
@Data
public class SendErrorVO {

    /** 会话 ID（可能为空：参数解析阶段失败时拿不到原始请求） */
    private Long conversationId;

    /** 客户端消息 ID（可能为空；此时前端把最后一条「发送中」气泡标记为失败） */
    private String clientMsgId;

    /** 原始消息内容（重发时复用，避免用户重新输入） */
    private String content;

    /** 业务错误码 */
    private Integer code;

    /** 失败原因（用户可见文案） */
    private String msg;
}
