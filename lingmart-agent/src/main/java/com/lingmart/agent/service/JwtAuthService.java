package com.lingmart.agent.service;

import com.lingmart.agent.dto.AgentCaller;
import com.lingmart.common.constant.AuthConstants;
import com.lingmart.common.exception.BizException;
import com.lingmart.common.jwt.JwtUtils;
import com.lingmart.common.result.ResultCode;
import io.jsonwebtoken.Claims;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 身份解析：Agent <b>自己校验 JWT</b>，不信任网关透传的 {@code X-User-Id}。
 *
 * <p><b>为什么不直接用 X-User-Id</b>：那是一个普通请求头，网关在正常链路上会覆写它，
 * 但网关的 {@code AuthGlobalFilter} 存在一个 fail-open 分支（{@code jti == null} 时直接放行、
 * <b>不注入也不覆写</b> X-User-Id），此时调用方自带的伪造身份头会被原样透传到下游。
 * 只要有人能直连 Agent 端口，就能任意指定"我是谁"。</p>
 *
 * <p>JWT 是自证的：签名由 auth 用共享密钥签发，Agent 用同一把密钥验签即可确认身份，
 * 不依赖任何中间环节的正确性。反正 Agent 本来就必须持有原始 JWT（要透传给网关调业务 API），
 * 所以这里验一次是零额外成本。</p>
 *
 * <p><b>已知边界</b>：Agent 不查网关的登出黑名单，所以「已登出但未过期」的 token 在
 * 绕过网关直连 Agent 时仍能通过验签。走网关的请求不会——网关会拒。这个缺口接受，
 * 因为直连端口本身就不该对外暴露。</p>
 */
@Slf4j
@Service
public class JwtAuthService {

    private static final String BEARER_PREFIX = "Bearer ";
    /** 未登录/凭证非法时的统一提示 */
    private static final String MSG_LOGIN_REQUIRED = "请先登录后使用助手";

    private final String secret;

    public JwtAuthService(@Value("${lingmart.jwt.secret}") String secret) {
        this.secret = secret;
    }

    /**
     * 解析 {@code Authorization} 头，得到调用者身份。
     *
     * @param authorizationHeader 形如 {@code Bearer <jwt>}（可为空）
     * @return 调用者身份（含原始 token，供透传）
     * @throws BizException 缺失、格式非法、验签失败或已过期（统一 code=401，不区分原因以免泄露校验细节）
     */
    public AgentCaller resolve(String authorizationHeader) {
        String token = extractToken(authorizationHeader);
        if (token == null) {
            throw new BizException(ResultCode.UNAUTHORIZED.getCode(), MSG_LOGIN_REQUIRED);
        }
        try {
            Claims claims = JwtUtils.parseToken(token, secret);
            Long userId = parseUserId(claims.getSubject());
            if (userId == null) {
                throw new BizException(ResultCode.UNAUTHORIZED.getCode(), MSG_LOGIN_REQUIRED);
            }
            Object role = claims.get(JwtUtils.CLAIM_ROLE);
            return new AgentCaller(userId, role == null ? null : String.valueOf(role), token);
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            // 只在 debug 级别记原因，不把 token 内容写进日志
            log.debug("JWT 校验失败：{}", e.getMessage());
            throw new BizException(ResultCode.UNAUTHORIZED.getCode(), MSG_LOGIN_REQUIRED);
        }
    }

    /**
     * 解析并要求管理员身份（角色取自 JWT 的 {@code role} 声明，而非请求头）。
     *
     * <p><b>为什么不能只校验 {@code X-User-Role} 头</b>：那是客户端可自带的普通请求头。
     * 在本服务端口可达的前提下，`curl -H "X-User-Role: ROLE_ADMIN"` 就能通过——
     * 不需要任何 token。管理员端点（知识库重建、审计查询）必须回到"验签 + 从 JWT 取角色"。</p>
     *
     * @throws BizException 未登录/凭证非法（401），或角色不是 ROLE_ADMIN（403）
     */
    public AgentCaller resolveAdmin(String authorizationHeader) {
        AgentCaller caller = resolve(authorizationHeader);
        if (!AuthConstants.ROLE_ADMIN.equals(caller.userRole())) {
            throw new BizException(ResultCode.FORBIDDEN.getCode(), "无权访问");
        }
        return caller;
    }

    /** 从 Authorization 头取出裸 token；非 Bearer 形式或为空返回 null。 */
    private static String extractToken(String authorizationHeader) {
        if (!StringUtils.hasText(authorizationHeader)) {
            return null;
        }
        String value = authorizationHeader.trim();
        if (!value.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            return null;
        }
        String token = value.substring(BEARER_PREFIX.length()).trim();
        return token.isEmpty() ? null : token;
    }

    private static Long parseUserId(String subject) {
        if (!StringUtils.hasText(subject)) {
            return null;
        }
        try {
            return Long.parseLong(subject.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
