package com.lingmart.common.dto;

import lombok.Data;

import java.io.Serializable;

/**
 * 用户公开信息（跨服务契约：auth 经内部接口提供给 shop/chat 展示昵称、头像、手机号等）。
 *
 * <p>注意：仅用于服务间内部接口；对外暴露时各服务须自行裁剪（如店铺主页不返回手机号）。</p>
 */
@Data
public class UserInfo implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 用户 ID */
    private Long id;

    /** 手机号（内部接口携带；对外展示按业务裁剪） */
    private String phone;

    /** 昵称 */
    private String nickname;

    /** 头像地址 */
    private String avatar;

    /** 角色：ROLE_USER / ROLE_SELLER / ROLE_ADMIN */
    private String role;
}
