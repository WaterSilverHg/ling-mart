package com.lingmart.agent.controller;

import com.lingmart.agent.entity.AgentAuditLog;
import com.lingmart.agent.service.AgentAuditService;
import com.lingmart.agent.service.JwtAuthService;
import com.lingmart.common.result.PageResult;
import com.lingmart.common.result.R;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;

/**
 * 操作审计查询接口（仅 ROLE_ADMIN）。
 *
 * <p>与 {@link AgentKbAdminController} 同理：路径含 {@code /admin} 但不在网关 {@code /api/admin/**} 段，
 * 故在本控制器内自己把关——<b>验签 JWT 并取其中的角色声明</b>，而不是读可伪造的 {@code X-User-Role} 头。</p>
 *
 * <p>典型用法：按 {@code sessionId} 拉一条会话的完整操作链，还原「AI 建议了什么 → 用户点了什么 → 结果如何」；
 * 或按 {@code eventType=receipt_rejected} 筛出被拒回执，排查伪造/重放。</p>
 */
@Slf4j
@RestController
public class AgentAuditAdminController {

    private final AgentAuditService auditService;
    private final JwtAuthService jwtAuthService;

    public AgentAuditAdminController(AgentAuditService auditService, JwtAuthService jwtAuthService) {
        this.auditService = auditService;
        this.jwtAuthService = jwtAuthService;
    }

    /**
     * 分页查询审计日志（按时间倒序）。
     *
     * @param userId    可选，按用户过滤
     * @param sessionId 可选，按会话过滤
     * @param eventType 可选，按事件类型过滤（tool_proposed / user_confirmed / user_cancelled /
     *                  receipt_rejected / turn_completed / tool_run）
     * @param from      可选，起始时间（ISO，如 2026-09-21T00:00:00）
     * @param to        可选，结束时间（ISO）
     * @param page      页码（从 1 起）
     * @param size      每页条数（上限 200）
     */
    @GetMapping("/api/agent/admin/audit")
    public R<PageResult<AgentAuditLog>> audit(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestParam(required = false) Long userId,
            @RequestParam(required = false) String sessionId,
            @RequestParam(required = false) String eventType,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size) {
        jwtAuthService.resolveAdmin(authorization);
        return R.ok(auditService.query(userId, sessionId, eventType, from, to, page, size));
    }
}
