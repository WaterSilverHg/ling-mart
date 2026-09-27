package com.lingmart.chat.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 会话列表项（买家侧显示店铺信息，老板侧显示买家信息；对方始终为 {@code peer}）。
 */
@Data
public class ConversationVO {

    /** 会话 ID */
    private Long conversationId;

    /** 店铺 ID */
    private Long shopId;

    /** 店铺名称 */
    private String shopName;

    /** 店铺 logo */
    private String shopLogo;

    /** 对方用户信息（头像/昵称，手机号仅在会话窗口展示） */
    private PeerInfo peer;

    /** 最后一条消息摘要 */
    private String lastMessage;

    /** 最后消息时间 */
    private LocalDateTime lastTime;
}
