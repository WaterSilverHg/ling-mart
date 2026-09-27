package com.lingmart.chat.vo;

import lombok.Data;

/**
 * 会话对方的用户信息。
 *
 * <p>会话窗口内展示头像 + 昵称 + 手机号（明文，带一键复制）；
 * 会话列表预览仅取头像/昵称，不取手机号（由前端按场景使用）。</p>
 */
@Data
public class PeerInfo {

    /** 用户 ID */
    private Long userId;

    /** 昵称 */
    private String nickname;

    /** 头像地址 */
    private String avatar;

    /** 手机号（仅在会话窗口内展示） */
    private String phone;
}
