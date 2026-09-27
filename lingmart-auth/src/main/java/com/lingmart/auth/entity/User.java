package com.lingmart.auth.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户实体（对应表 lingmart_auth.user）。
 *
 * <p>角色 {@code role} 约定：ROLE_USER 普通用户 / ROLE_SELLER 卖家 / ROLE_ADMIN 管理员。
 * 密码 {@code password} 为 BCrypt 加密后的密文，禁止明文存储。</p>
 */
@Data
@TableName("user")
public class User {

    /** 主键（雪花，应用侧分配） */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 手机号（唯一索引） */
    private String phone;

    /** BCrypt 加密后的密码 */
    private String password;

    /** 昵称 */
    private String nickname;

    /** 头像地址 */
    private String avatar;

    /** 角色：ROLE_USER / ROLE_SELLER / ROLE_ADMIN */
    private String role;

    /** 状态：1 正常，0 禁用 */
    private Integer status;

    /** 创建时间 */
    private LocalDateTime createTime;

    /** 更新时间 */
    private LocalDateTime updateTime;
}
