package com.lingmart.chat.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 聊天会话实体（对应表 lingmart_chat.chat_conversation）。
 *
 * <p>一次「买家 × 店铺」唯一一条会话（买家与同一家店只有一条，反复进店复用同一会话）；
 * {@code shop_user_id} 为店主（老板）用户 ID，用于老板侧会话列表。</p>
 */
@Data
@TableName("chat_conversation")
public class ChatConversation {

    /** 主键（雪花，应用侧分配） */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 买家用户 ID */
    private Long buyerId;

    /** 店铺 ID */
    private Long shopId;

    /** 店主（老板）用户 ID */
    private Long shopUserId;

    /** 最后一条消息摘要（会话列表展示） */
    private String lastMessage;

    /** 最后一条消息时间 */
    private LocalDateTime lastTime;

    /** 创建时间 */
    private LocalDateTime createTime;

    /** 更新时间 */
    private LocalDateTime updateTime;
}
