package com.lingmart.chat.vo;

import lombok.Data;

/**
 * 会话详情（发起/进入会话时返回，用于渲染聊天窗口头部）。
 */
@Data
public class ConversationDetailVO {

    /** 会话 ID */
    private Long conversationId;

    /** 店铺 ID */
    private Long shopId;

    /** 店铺名称 */
    private String shopName;

    /** 店铺 logo */
    private String shopLogo;

    /** 对方用户信息（头像/昵称/手机号，会话窗口内展示） */
    private PeerInfo peer;

    /** 当前用户 ID */
    private Long myId;
}
