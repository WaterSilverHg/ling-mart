package com.lingmart.chat.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 聊天消息视图（历史记录与实时推送共用）。
 */
@Data
public class MessageVO {

    /** 消息 ID */
    private Long id;

    /** 会话 ID */
    private Long conversationId;

    /** 发送者用户 ID */
    private Long senderId;

    /** 发送者昵称 */
    private String senderNickname;

    /** 发送者头像 */
    private String senderAvatar;

    /** 消息内容 */
    private String content;

    /** 发送时间 */
    private LocalDateTime createTime;

    /**
     * 客户端消息 ID（发送方生成，仅实时推送时原样回显）。
     *
     * <p>不入库：历史记录（分页/增量补拉）中该字段恒为 null。前端靠它把
     * 「发送中」的乐观气泡替换成服务端确认后的正式消息。</p>
     */
    private String clientMsgId;
}
