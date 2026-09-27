package com.lingmart.chat.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 聊天消息实体（对应表 lingmart_chat.chat_message）。
 *
 * <p>消息持久化，历史记录经 REST 分页拉取；实时推送走 WebSocket(STOMP) topic。</p>
 */
@Data
@TableName("chat_message")
public class ChatMessage {

    /** 主键（雪花，应用侧分配） */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 所属会话 ID */
    private Long conversationId;

    /** 发送者用户 ID */
    private Long senderId;

    /** 接收者用户 ID */
    private Long receiverId;

    /** 消息内容 */
    private String content;

    /** 消息类型：1 文本 */
    private Integer msgType;

    /** 发送时间 */
    private LocalDateTime createTime;
}
