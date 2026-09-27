package com.lingmart.auth.service;

import com.lingmart.auth.dto.LoginDTO;
import com.lingmart.auth.dto.RegisterDTO;
import com.lingmart.auth.dto.UpdateProfileDTO;
import com.lingmart.auth.vo.LoginVO;
import com.lingmart.auth.vo.UserVO;

import java.util.List;

/**
 * 用户服务接口。
 */
public interface UserService {

    /**
     * 用户注册（手机号 + 验证码 + 密码）。
     *
     * @param dto 注册参数
     */
    void register(RegisterDTO dto);

    /**
     * 用户登录，签发 JWT。
     *
     * @param dto 登录参数
     * @return 登录结果（token + 用户信息）
     */
    LoginVO login(LoginDTO dto);

    /**
     * 获取当前登录用户信息。
     *
     * @param userId 用户 ID（由网关透传的请求头解析）
     * @return 用户信息
     */
    UserVO getCurrentUser(Long userId);

    /**
     * 更新用户角色（供 lingmart-shop 审核通过后调用，将用户提升为卖家）。
     *
     * @param userId 用户 ID
     * @param role   目标角色（如 ROLE_SELLER）
     */
    void updateRole(Long userId, String role);

    /**
     * 更新当前用户资料（昵称 / 头像）。
     *
     * @param userId 用户 ID（由网关透传的请求头解析）
     * @param dto    待更新字段（非空字段才覆盖）
     * @return 更新后的用户信息
     */
    UserVO updateProfile(Long userId, UpdateProfileDTO dto);

    /**
     * 按用户 ID 查询用户信息（服务间内部调用：聊天/店铺主页展示昵称、头像、手机号等）。
     *
     * @param userId 用户 ID
     * @return 用户信息；用户不存在时返回 null
     */
    UserVO getUserInfo(Long userId);

    /**
     * 按用户 ID 集合批量查询用户信息（服务间内部调用：会话列表等批量聚合，避免 N+1 逐个 Feign）。
     *
     * @param userIds 用户 ID 列表
     * @return 用户信息列表（调用方按 id 建 Map 使用；未命中的 id 不占位）
     */
    List<UserVO> listUserInfo(List<Long> userIds);

    /**
     * 按手机号查询用户信息（管理端角色修正入口用）。
     *
     * @param phone 手机号（精确匹配）
     * @return 用户信息；用户不存在时返回 null
     */
    UserVO getUserInfoByPhone(String phone);

    /**
     * 退出登录：将当前 token（jti）加入 Redis 黑名单直至其自然过期，实现服务端失效。
     *
     * <p>无状态 JWT 本身无法主动作废，网关在鉴权时校验黑名单（见 lingmart-gateway
     * AuthGlobalFilter），命中即判 401。token 非法/过期时静默忽略（幂等）。</p>
     *
     * @param auth Authorization 请求头（形如 {@code Bearer <token>}）；可为空
     */
    void logout(String auth);
}
