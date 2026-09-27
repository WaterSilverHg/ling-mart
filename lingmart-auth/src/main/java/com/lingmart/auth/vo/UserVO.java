package com.lingmart.auth.vo;

import lombok.Data;

/**
 * 用户信息视图对象（对外暴露的脱敏用户信息）。
 */
@Data
public class UserVO {

    /** 用户 ID */
    private Long id;

    /** 手机号 */
    private String phone;

    /** 昵称 */
    private String nickname;

    /** 头像地址 */
    private String avatar;

    /** 角色：ROLE_USER / ROLE_SELLER / ROLE_ADMIN */
    private String role;
}
