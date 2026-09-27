package com.lingmart.auth.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 登录成功返回视图对象（JWT + 用户信息）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class LoginVO {

    /** JWT 访问令牌 */
    private String token;

    /** 当前登录用户信息 */
    private UserVO user;
}
