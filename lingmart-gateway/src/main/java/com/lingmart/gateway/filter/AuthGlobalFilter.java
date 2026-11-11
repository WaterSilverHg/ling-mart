package com.lingmart.gateway.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lingmart.common.constant.AuthConstants;
import com.lingmart.common.jwt.JwtUtils;
import com.lingmart.common.result.R;
import com.lingmart.common.result.ResultCode;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * 网关 JWT 鉴权过滤器（白名单制）。
 *
 * <p>对非白名单路径统一校验 {@code Authorization: Bearer <token>}：
 * <ol>
 *   <li>校验失败返回 401 JSON；</li>
 *   <li>校验通过后校验退出登录黑名单（jti 在 Redis 中则视为已失效，返回 401）；</li>
 *   <li>均通过后将 userId / role 写入请求头透传下游服务。</li>
 * </ol>
 *
 * <p><b>无论走哪条分支，入站的 {@code X-User-Id} / {@code X-User-Role} 都会先被删除</b>。
 * 下游服务完全信任这两个头（{@code @RequestHeader} 直接当身份用），如果客户端自带的头
 * 能一路透传下去，就等于可以随意冒充任意用户。带 token 的路径上后面的 {@code header(...)}
 * 会覆盖同名头，风险不明显；但白名单路径原本是原样放行的，一旦新增一个读该头的白名单接口
 * 就立刻变成身份伪造。这里统一在入口删除，把「下游只可能收到网关写入的身份」变成结构性保证，
 * 而不是依赖每个接口自觉。</p>
 * 黑名单查询使用 Reactive Redis（WebFlux 不阻塞事件线程）；Redis 不可用时 fail-open 放行
 * （可用性优先，仅损失「已退出 token 的即时撤销」能力）。本过滤器与 Servlet 的 Filter 完全不同。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuthGlobalFilter implements GlobalFilter, Ordered {

    /** Ant 风格路径匹配器（用于白名单匹配） */
    private static final AntPathMatcher PATH_MATCHER = new AntPathMatcher();

    /** JSON 序列化器（写 401 响应体） */
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /** 退出登录黑名单 key 前缀（与 lingmart-auth 保持一致） */
    private static final String JWT_BLACKLIST_PREFIX = "lingmart:jwt:blacklist:";

    /** JWT 签名密钥（与 lingmart-auth 保持一致） */
    @Value("${lingmart.jwt.secret}")
    private String jwtSecret;

    /** 白名单路径列表（逗号分隔，无需登录） */
    @Value("${lingmart.gateway.whitelist:}")
    private List<String> whitelist;

    /** Reactive Redis（WebFlux 栈，黑名单查询不阻塞事件线程） */
    private final ReactiveStringRedisTemplate redisTemplate;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        // 0. 先剥离客户端自带的身份头：下游只允许收到网关解析 JWT 后写入的身份
        ServerHttpRequest request = stripIdentityHeaders(exchange.getRequest());
        String path = request.getURI().getPath();

        // 1. 白名单路径直接放行（如注册 / 登录 / 验证码），同样使用已剥离身份头的请求
        if (isWhitelist(path)) {
            return chain.filter(exchange.mutate().request(request).build());
        }

        // 2. 提取并校验 Token
        String token = resolveToken(request);
        if (token == null) {
            return unauthorized(exchange, "未登录或登录已过期");
        }
        Claims claims;
        try {
            claims = JwtUtils.parseToken(token, jwtSecret);
        } catch (Exception e) {
            log.warn("JWT 校验失败：path={}, err={}", path, e.getMessage());
            return unauthorized(exchange, "未登录或登录已过期");
        }

        // 3. 管理端路径（/api/admin/**）：仅 ROLE_ADMIN 可访问，普通用户/卖家一律 403
        if (path.startsWith("/api/admin/")) {
            String role = claims.get(JwtUtils.CLAIM_ROLE, String.class);
            if (!AuthConstants.ROLE_ADMIN.equals(role)) {
                return forbidden(exchange, "无权访问管理端");
            }
        }

        // 4. 把 token 里的身份写入请求头（基于已剥离身份头的 request，不会残留客户端伪造值）
        ServerHttpRequest mutated = request.mutate()
                .header(AuthConstants.HEADER_USER_ID, claims.getSubject())
                .header(AuthConstants.HEADER_USER_ROLE, claims.get(JwtUtils.CLAIM_ROLE, String.class))
                .build();

        // 5. 校验退出登录黑名单：jti 命中则已撤销，拒绝访问。
        //    无 jti 的旧 token 无法撤销，直接放行——但必须走 stripped 后的请求
        String jti = claims.getId();
        if (jti == null) {
            return chain.filter(exchange.mutate().request(mutated).build());
        }
        return redisTemplate.hasKey(JWT_BLACKLIST_PREFIX + jti)
                .flatMap(revoked -> {
                    if (Boolean.TRUE.equals(revoked)) {
                        log.info("命中退出登录黑名单，拒绝访问：path={}, userId={}", path, claims.getSubject());
                        return unauthorized(exchange, "登录已失效，请重新登录");
                    }
                    return chain.filter(exchange.mutate().request(mutated).build());
                })
                // Redis 不可用：fail-open 放行（可用性优先），仅告警
                .onErrorResume(e -> {
                    log.warn("JWT 黑名单检查失败，放行：path={}, err={}", path, e.getMessage());
                    return chain.filter(exchange.mutate().request(mutated).build());
                });
    }

    @Override
    public int getOrder() {
        // 高优先级，最先执行鉴权
        return -100;
    }

    /**
     * 删除入站请求里的身份头，返回一份修改后的副本（原始请求不变）。
     *
     * <p>下游服务完全信任 {@code X-User-Id} / {@code X-User-Role}，
     * 任何一条能带着客户端自带值到达下游的路径都是身份伪造入口。</p>
     *
     * @param request 原始请求
     * @return 已移除身份头的请求副本
     */
    private static ServerHttpRequest stripIdentityHeaders(ServerHttpRequest request) {
        return request.mutate()
                .headers(headers -> {
                    headers.remove(AuthConstants.HEADER_USER_ID);
                    headers.remove(AuthConstants.HEADER_USER_ROLE);
                })
                .build();
    }

    /**
     * 判断请求路径是否命中白名单。
     *
     * @param path 请求路径
     * @return true 表示放行
     */
    private boolean isWhitelist(String path) {
        return whitelist.stream().anyMatch(pattern -> PATH_MATCHER.match(pattern, path));
    }

    /**
     * 从 Authorization 请求头解析 Bearer Token。
     *
     * @param request 请求
     * @return Token 字符串；无有效 Bearer 头时返回 null
     */
    private String resolveToken(ServerHttpRequest request) {
        String auth = request.getHeaders().getFirst(AuthConstants.HEADER_AUTHORIZATION);
        if (auth != null && auth.startsWith(AuthConstants.TOKEN_PREFIX)) {
            return auth.substring(AuthConstants.TOKEN_PREFIX.length());
        }
        return null;
    }

    /**
     * 写入 401 未认证 JSON 响应。
     *
     * @param exchange 交换对象
     * @param msg      提示信息
     * @return 响应完成信号
     */
    private Mono<Void> unauthorized(ServerWebExchange exchange, String msg) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        try {
            byte[] bytes = OBJECT_MAPPER.writeValueAsBytes(
                    R.fail(ResultCode.UNAUTHORIZED.getCode(), msg));
            DataBuffer buffer = response.bufferFactory().wrap(bytes);
            return response.writeWith(Mono.just(buffer));
        } catch (Exception e) {
            log.error("网关响应序列化失败", e);
            return response.setComplete();
        }
    }

    /**
     * 写入 403 无权限 JSON 响应（管理端路径角色拦截）。
     *
     * @param exchange 交换对象
     * @param msg      提示信息
     * @return 响应完成信号
     */
    private Mono<Void> forbidden(ServerWebExchange exchange, String msg) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.FORBIDDEN);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        try {
            byte[] bytes = OBJECT_MAPPER.writeValueAsBytes(
                    R.fail(ResultCode.FORBIDDEN.getCode(), msg));
            DataBuffer buffer = response.bufferFactory().wrap(bytes);
            return response.writeWith(Mono.just(buffer));
        } catch (Exception e) {
            log.error("网关响应序列化失败", e);
            return response.setComplete();
        }
    }
}
