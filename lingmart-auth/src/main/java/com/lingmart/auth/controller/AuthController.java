package com.lingmart.auth.controller;

import com.lingmart.auth.dto.LoginDTO;
import com.lingmart.auth.dto.RegisterDTO;
import com.lingmart.auth.dto.UpdateProfileDTO;
import com.lingmart.auth.service.UserService;
import com.lingmart.auth.service.VerificationCodeService;
import com.lingmart.auth.vo.LoginVO;
import com.lingmart.auth.vo.UserVO;
import com.lingmart.common.constant.AuthConstants;
import com.lingmart.common.exception.BizException;
import com.lingmart.common.result.R;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.regex.Pattern;

/**
 * 认证中心接口。
 *
 * <p>路径前缀 {@code /api/auth}。注册/登录/验证码为公开接口（网关白名单放行）；
 * {@code /user/me} 需要登录，网关解析 JWT 后通过 {@code X-User-Id} 请求头透传用户 ID。</p>
 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    /** 中国大陆手机号正则 */
    private static final Pattern PHONE_PATTERN = Pattern.compile("^1\\d{10}$");

    /** 开发模式开关：为 true 时 /code/send 返回验证码明文便于联调；生产必须保持关闭 */
    @Value("${lingmart.auth.dev-code-enabled:false}")
    private boolean devCodeEnabled;

    private final UserService userService;
    private final VerificationCodeService verificationCodeService;

    /**
     * 发送短信验证码。
     *
     * <p>仅在 {@code lingmart.auth.dev-code-enabled=true}（开发模式）时返回验证码明文，
     * 便于前端自动回填联调；生产默认关闭，验证码经 Redis 存储、不对外暴露。</p>
     *
     * @param phone 手机号
     * @return 开发模式下附带验证码明文（key 为 devCode），否则为空 Map
     */
    @PostMapping("/code/send")
    public R<Map<String, String>> sendCode(@RequestParam String phone) {
        if (!StringUtils.hasText(phone) || !PHONE_PATTERN.matcher(phone).matches()) {
            throw new BizException("手机号格式不正确");
        }
        String code = verificationCodeService.send(phone);
        if (devCodeEnabled) {
            return R.ok(Map.of("devCode", code));
        }
        return R.ok(Map.of());
    }

    /**
     * 用户注册。
     *
     * @param dto 注册参数（手机号 + 验证码 + 密码 + 昵称）
     * @return 成功提示
     */
    @PostMapping("/register")
    public R<Void> register(@Valid @RequestBody RegisterDTO dto) {
        userService.register(dto);
        return R.ok();
    }

    /**
     * 用户登录，签发 JWT。
     *
     * @param dto 登录参数（手机号 + 密码）
     * @return token 与用户信息
     */
    @PostMapping("/login")
    public R<LoginVO> login(@Valid @RequestBody LoginDTO dto) {
        return R.ok(userService.login(dto));
    }

    /**
     * 查询当前登录用户信息。
     *
     * @param userId 网关透传的用户 ID 请求头
     * @return 当前用户信息
     */
    @GetMapping("/user/me")
    public R<UserVO> me(
            @RequestHeader(value = AuthConstants.HEADER_USER_ID, required = false) Long userId) {
        return R.ok(userService.getCurrentUser(userId));
    }

    /**
     * 更新当前用户资料（昵称 / 头像）。
     *
     * <p>头像地址由上传接口返回（/uploads/...）；仅非空字段生效。</p>
     *
     * @param dto    待更新字段（昵称/头像）
     * @param userId 网关透传的用户 ID 请求头
     * @return 更新后的用户信息
     */
    @PutMapping("/user/me")
    public R<UserVO> updateProfile(@Valid @RequestBody UpdateProfileDTO dto,
                                   @RequestHeader(value = AuthConstants.HEADER_USER_ID, required = false) Long userId) {
        return R.ok(userService.updateProfile(userId, dto));
    }

    /**
     * 退出登录（服务端失效）。
     *
     * <p>将当前 token 的 jti 加入 Redis 黑名单直至其自然过期，网关在后续请求鉴权时
     * 命中黑名单即判未登录（401），实现无状态 JWT 的服务端撤销。token 为空或已失效时幂等返回成功。</p>
     *
     * @param auth 认证请求头（形如 {@code Bearer <token>}）
     * @return 成功提示
     */
    @PostMapping("/logout")
    public R<Void> logout(
            @RequestHeader(value = AuthConstants.HEADER_AUTHORIZATION, required = false) String auth) {
        userService.logout(auth);
        return R.ok();
    }
}
