package com.lingmart.auth.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.lingmart.auth.dto.LoginDTO;
import com.lingmart.auth.dto.RegisterDTO;
import com.lingmart.auth.dto.UpdateProfileDTO;
import com.lingmart.auth.entity.User;
import com.lingmart.auth.mapper.UserMapper;
import com.lingmart.common.util.RedisRateLimiter;
import com.lingmart.auth.service.UserService;
import com.lingmart.auth.service.VerificationCodeService;
import com.lingmart.auth.vo.LoginVO;
import com.lingmart.auth.vo.UserVO;
import com.lingmart.common.constant.AuthConstants;
import com.lingmart.common.exception.BizException;
import com.lingmart.common.jwt.JwtUtils;
import com.lingmart.common.result.ResultCode;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.Date;
import java.util.List;
import java.util.Set;

/**
 * 用户服务实现。
 *
 * <p>核心逻辑：注册（校验验证码 + 查重 + BCrypt 加密入库）、登录（校验密码 + 签发 JWT）、
 * 当前用户查询。Token 使用无状态 JWT，网关负责校验并透传用户身份。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    private final UserMapper userMapper;
    private final VerificationCodeService verificationCodeService;
    private final BCryptPasswordEncoder passwordEncoder;
    private final RedisRateLimiter rateLimiter;
    private final StringRedisTemplate redisTemplate;

    /** 退出登录黑名单 key 前缀 */
    private static final String JWT_BLACKLIST_PREFIX = "lingmart:jwt:blacklist:";

    /** 允许写入的角色集合（角色值参与鉴权字符串比对，必须是封闭枚举） */
    private static final Set<String> ASSIGNABLE_ROLES = Set.of(
            AuthConstants.ROLE_USER, AuthConstants.ROLE_SELLER, AuthConstants.ROLE_ADMIN);

    /** JWT 签名密钥（与网关保持一致） */
    @Value("${lingmart.jwt.secret}")
    private String jwtSecret;

    /** JWT 有效期（分钟） */
    @Value("${lingmart.jwt.expire-minutes}")
    private long expireMinutes;

    @Override
    public void register(RegisterDTO dto) {
        // 1. 校验短信验证码
        if (!verificationCodeService.verify(dto.getPhone(), dto.getCode())) {
            throw new BizException("验证码错误");
        }

        // 2. 校验手机号是否已被注册
        Long count = userMapper.selectCount(
                Wrappers.<User>lambdaQuery().eq(User::getPhone, dto.getPhone()));
        if (count != null && count > 0) {
            throw new BizException("该手机号已注册");
        }

        // 3. 密码 BCrypt 加密后入库，新用户默认普通角色
        User user = new User();
        user.setPhone(dto.getPhone());
        user.setPassword(passwordEncoder.encode(dto.getPassword()));
        user.setNickname(dto.getNickname());
        user.setRole(AuthConstants.ROLE_USER);
        user.setStatus(1);
        userMapper.insert(user);
        log.info("用户注册成功：userId={}, phone={}", user.getId(), user.getPhone());
    }

    @Override
    public LoginVO login(LoginDTO dto) {
        // 0. 防爆破：5 分钟内登录失败 ≥5 次则临时锁定
        if (rateLimiter.hit("login-fail:" + dto.getPhone(), 5 * 60_000L) >= 5) {
            throw new BizException("登录失败次数过多，请 5 分钟后再试");
        }
        // 1. 按手机号查询用户
        User user = userMapper.selectOne(
                Wrappers.<User>lambdaQuery().eq(User::getPhone, dto.getPhone()));
        if (user == null || !passwordEncoder.matches(dto.getPassword(), user.getPassword())) {
            throw new BizException("手机号或密码错误");
        }
        if (user.getStatus() == null || user.getStatus() != 1) {
            throw new BizException("账号已被禁用，请联系管理员");
        }

        // 登录成功：重置失败计数
        rateLimiter.reset("login-fail:" + dto.getPhone());

        // 2. 签发无状态 JWT（含用户 ID 与角色）
        String token = JwtUtils.generateToken(
                user.getId(), user.getRole(), jwtSecret, expireMinutes * 60 * 1000);
        return new LoginVO(token, toUserVO(user));
    }

    @Override
    public UserVO getCurrentUser(Long userId) {
        if (userId == null) {
            throw new BizException(ResultCode.UNAUTHORIZED.getCode(), "未登录或登录已过期");
        }
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BizException(ResultCode.UNAUTHORIZED.getCode(), "用户不存在");
        }
        return toUserVO(user);
    }

    @Override
    public void updateRole(Long userId, String role) {
        if (userId == null) {
            throw new BizException("用户ID不能为空");
        }
        // 角色必须来自已知枚举：此前是「传什么写什么」，一旦有任意写入入口
        // （内部接口、管理端、后续新增的批量工具），就能把 role 写成任意字符串，
        // 而下游/网关全是按字符串精确比对做鉴权的——写入脏值等于制造无法解释的权限状态
        if (!ASSIGNABLE_ROLES.contains(role)) {
            log.warn("拒绝非法角色更新：userId={}, role={}", userId, role);
            throw new BizException("非法的角色值：" + role);
        }
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BizException("用户不存在");
        }
        User update = new User();
        update.setId(userId);
        update.setRole(role);
        userMapper.updateById(update);
        log.info("用户角色更新：userId={}, role={}", userId, role);
    }

    @Override
    public UserVO getUserInfo(Long userId) {
        if (userId == null) {
            return null;
        }
        User user = userMapper.selectById(userId);
        return user == null ? null : toUserVO(user);
    }

    @Override
    public List<UserVO> listUserInfo(List<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return List.of();
        }
        // 一次 IN 查询取全部用户，再统一映射（未命中的 id 自然不在结果里）
        return userMapper.selectBatchIds(userIds).stream()
                .map(this::toUserVO)
                .toList();
    }

    @Override
    public UserVO getUserInfoByPhone(String phone) {
        if (!StringUtils.hasText(phone)) {
            return null;
        }
        User user = userMapper.selectOne(Wrappers.<User>lambdaQuery().eq(User::getPhone, phone));
        return user == null ? null : toUserVO(user);
    }

    @Override
    public UserVO updateProfile(Long userId, UpdateProfileDTO dto) {
        if (userId == null) {
            throw new BizException(ResultCode.UNAUTHORIZED.getCode(), "未登录或登录已过期");
        }
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BizException(ResultCode.UNAUTHORIZED.getCode(), "用户不存在");
        }
        User update = new User();
        update.setId(userId);
        if (StringUtils.hasText(dto.getNickname())) {
            update.setNickname(dto.getNickname());
        }
        if (StringUtils.hasText(dto.getAvatar())) {
            update.setAvatar(dto.getAvatar());
        }
        userMapper.updateById(update);
        User fresh = userMapper.selectById(userId);
        log.info("用户资料更新：userId={}, nickname={}, avatar={}", userId, fresh.getNickname(), fresh.getAvatar());
        return toUserVO(fresh);
    }

    @Override
    public void logout(String auth) {
        String token = resolveBearerToken(auth);
        if (token == null) {
            return; // 无 token，无需撤销（幂等）
        }
        try {
            Claims claims = JwtUtils.parseToken(token, jwtSecret);
            String jti = claims.getId();
            Date expiration = claims.getExpiration();
            if (jti == null || expiration == null) {
                log.warn("退出登录：token 缺少 jti/过期时间，跳过黑名单，userId={}", claims.getSubject());
                return;
            }
            // 黑名单有效期 = 剩余有效期，到期自动过期（无需清理任务）
            long ttlMillis = Math.max(expiration.getTime() - System.currentTimeMillis(), 1);
            redisTemplate.opsForValue().set(JWT_BLACKLIST_PREFIX + jti, "1", Duration.ofMillis(ttlMillis));
            log.info("退出登录，token 已加入黑名单：userId={}, ttl={}ms", claims.getSubject(), ttlMillis);
        } catch (Exception e) {
            // token 已过期/非法：本就无法再通过网关鉴权，静默忽略
            log.warn("退出登录解析 token 失败（忽略）：err={}", e.getMessage());
        }
    }

    /** 从 Authorization 请求头解析 Bearer Token（无有效头返回 null） */
    private String resolveBearerToken(String auth) {
        if (auth != null && auth.startsWith(AuthConstants.TOKEN_PREFIX)) {
            return auth.substring(AuthConstants.TOKEN_PREFIX.length());
        }
        return null;
    }

    /**
     * 实体转视图对象（不暴露密码等敏感字段）。
     *
     * @param user 用户实体
     * @return 用户视图对象
     */
    private UserVO toUserVO(User user) {
        UserVO vo = new UserVO();
        vo.setId(user.getId());
        vo.setPhone(user.getPhone());
        vo.setNickname(StringUtils.hasText(user.getNickname()) ? user.getNickname() : user.getPhone());
        vo.setAvatar(user.getAvatar());
        vo.setRole(user.getRole());
        return vo;
    }
}
