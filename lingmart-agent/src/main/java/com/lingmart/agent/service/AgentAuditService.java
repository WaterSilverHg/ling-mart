package com.lingmart.agent.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lingmart.agent.config.AgentProperties;
import com.lingmart.agent.dto.AgentToolCall;
import com.lingmart.agent.dto.ToolExecutionResult;
import com.lingmart.agent.entity.AgentAuditLog;
import com.lingmart.agent.mapper.AgentAuditLogMapper;
import com.lingmart.common.result.PageResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Agent 操作审计：把"AI 建议了什么、用户确认了什么、结果如何"落库，供事后追溯。
 *
 * <p><b>为什么必须落库而不是继续放 Redis</b>：本项目的 Redis 用的是官方镜像声明的匿名卷 + 默认 RDB 快照
 * （`docker compose down && up` 后匿名卷不复用、快照需 100 改/300s 才触发、无 AOF），
 * 一次常规的容器重建就会丢掉全部历史——"会悄悄丢历史的审计日志"比没有更危险，因为它会被信任。</p>
 *
 * <p><b>失败容忍</b>：所有写入都是 try-catch + 告警，绝不向上抛。Agent 是可选组件
 * （不纳入 verify-health.sh / 「全部服务」），不能因为审计库没建好就整个不可用。</p>
 */
@Slf4j
@Service
public class AgentAuditService {

    // ---------------- 事件类型 ----------------

    /** 服务端向用户下发了操作意图（确认卡片） */
    public static final String EVENT_TOOL_PROPOSED = "tool_proposed";
    /** 只读工具被服务端自动执行（阶段 3 预留） */
    public static final String EVENT_TOOL_RUN = "tool_run";
    /** 用户确认并执行了操作 */
    public static final String EVENT_USER_CONFIRMED = "user_confirmed";
    /** 用户取消了操作 */
    public static final String EVENT_USER_CANCELLED = "user_cancelled";
    /** 收到未匹配到下发记录的回执（伪造/重放/卡片失效）——安全相关，务必可查 */
    public static final String EVENT_RECEIPT_REJECTED = "receipt_rejected";
    /** 一轮对话结束 */
    public static final String EVENT_TURN_COMPLETED = "turn_completed";

    /** 用户动作取值 */
    private static final String ACTION_CONFIRM = "confirm";
    private static final String ACTION_CANCEL = "cancel";

    /** 参数 JSON 落库上限（超过则截断，避免单条记录异常膨胀） */
    private static final int MAX_ARGUMENTS_CHARS = 1000;

    private final AgentAuditLogMapper auditMapper;
    private final AgentProperties properties;
    private final ObjectMapper objectMapper;

    public AgentAuditService(AgentAuditLogMapper auditMapper, AgentProperties properties, ObjectMapper objectMapper) {
        this.auditMapper = auditMapper;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    // ---------------- 写入 ----------------

    /**
     * 记录「下发操作意图」。每张确认卡片一条，所以用户看到过什么一定查得到。
     *
     * @param tools 本轮实际下发给用户的操作（已按单轮上限截断）
     */
    public void recordToolProposed(Long userId, String sessionId, List<AgentToolCall> tools, String messageExcerpt) {
        if (CollectionUtils.isEmpty(tools)) {
            return;
        }
        for (AgentToolCall tool : tools) {
            AgentAuditLog row = base(userId, sessionId, EVENT_TOOL_PROPOSED);
            row.setToolCallId(tool.toolCallId());
            row.setToolName(tool.name());
            row.setAction(tool.action());
            row.setArguments(toJson(tool.arguments()));
            row.setPurpose(tool.purpose());
            row.setResult("proposed");
            row.setMessageExcerpt(excerpt(messageExcerpt));
            insert(row);
        }
    }

    /**
     * 记录一批回执的校验结果：命中的按用户动作记为已确认/已取消，未命中的记为被拒。
     *
     * <p>未命中的回执单独成事件类型（而非混进"用户取消"），因为它是<b>安全信号</b>——
     * 伪造、重放、或卡片失效都会落到这里，排障与风控都需要能一眼筛出来。</p>
     */
    public void recordReceipts(Long userId, String sessionId,
                               List<ToolExecutionResult> matched, List<ToolExecutionResult> unmatched) {
        if (!CollectionUtils.isEmpty(matched)) {
            for (ToolExecutionResult receipt : matched) {
                boolean cancelled = ACTION_CANCEL.equalsIgnoreCase(receipt.outcome());
                AgentAuditLog row = base(userId, sessionId,
                        cancelled ? EVENT_USER_CANCELLED : EVENT_USER_CONFIRMED);
                row.setAction(receipt.action());
                row.setPurpose(receipt.purpose());
                row.setUserAction(cancelled ? ACTION_CANCEL : ACTION_CONFIRM);
                row.setResult(receipt.success() ? "success" : "failure");
                row.setErrorMsg(receipt.success() ? null : truncate(receipt.message(), 500));
                insert(row);
            }
        }
        if (!CollectionUtils.isEmpty(unmatched)) {
            for (ToolExecutionResult receipt : unmatched) {
                AgentAuditLog row = base(userId, sessionId, EVENT_RECEIPT_REJECTED);
                row.setAction(receipt.action());
                row.setPurpose(receipt.purpose());
                row.setResult("rejected");
                row.setErrorMsg("回执未匹配到服务端下发记录（nonce 校验未命中）");
                insert(row);
            }
        }
    }

    /**
     * 记录一次「只读工具被服务端自动执行」。
     *
     * <p>只读工具免用户确认，等于服务端替用户发了业务请求——**没有用户点击这个动作可追溯，
     * 所以审计更不能省**。这是排查"Agent 到底查了什么、查了几次"的唯一入口。</p>
     *
     * @param arguments 调用参数的紧凑表示（非原文 JSON，避免敏感内容）
     * @param result    结果：{@code ok} / {@code cache_hit} / {@code quota_exceeded} / {@code failed}
     * @param errorMsg  失败原因（成功时传 null）
     */
    public void recordToolRun(Long userId, String sessionId, String toolName,
                              String arguments, String result, String errorMsg) {
        AgentAuditLog row = base(userId, sessionId, EVENT_TOOL_RUN);
        row.setToolName(toolName);
        // arguments 列限长 1024，这里再收敛一次
        row.setArguments(truncate(arguments, 1000));
        row.setResult(result);
        row.setErrorMsg(truncate(errorMsg, 500));
        insert(row);
    }

    /**
     * 记录一轮对话结束。
     *
     * @param result 本轮最终形态：{@code text}（成文回答）/ {@code tool}（发起操作意图）/ {@code empty}（模型无输出）。
     *               注意：LLM 超时与异常不产生本事件（它们在 {@code AgentTurnService} 内部就降级返回了），
     *               排查那类问题请看日志里的 `Agent LLM 调用 sessionId=… 结果=timeout/error` 行，
     *               可按 sessionId + 时间与本表关联。
     */
    public void recordTurnCompleted(Long userId, String sessionId, String result, String errorMsg) {
        AgentAuditLog row = base(userId, sessionId, EVENT_TURN_COMPLETED);
        row.setResult(result);
        row.setErrorMsg(truncate(errorMsg, 500));
        insert(row);
    }

    /** 组装公共字段。 */
    private AgentAuditLog base(Long userId, String sessionId, String eventType) {
        AgentAuditLog row = new AgentAuditLog();
        row.setUserId(userId);
        row.setSessionId(sessionId);
        row.setEventType(eventType);
        row.setTs(LocalDateTime.now());
        return row;
    }

    /** 同步插入；任何失败只告警，绝不阻断对话。 */
    private void insert(AgentAuditLog row) {
        if (!properties.getAudit().isEnabled()) {
            return;
        }
        try {
            auditMapper.insert(row);
        } catch (Exception e) {
            log.warn("写审计日志失败（不影响对话）：eventType={} sessionId={} err={}",
                    row.getEventType(), row.getSessionId(), e.getMessage());
        }
    }

    // ---------------- 查询 ----------------

    /**
     * 按条件分页查询审计日志（管理端）。
     *
     * @param userId    可选，按用户过滤
     * @param sessionId 可选，按会话过滤
     * @param eventType 可选，按事件类型过滤
     * @param from      可选，起始时间（含）
     * @param to        可选，结束时间（含）
     */
    public PageResult<AgentAuditLog> query(Long userId, String sessionId, String eventType,
                                           LocalDateTime from, LocalDateTime to, long page, long size) {
        LambdaQueryWrapper<AgentAuditLog> wrapper = new LambdaQueryWrapper<AgentAuditLog>()
                .eq(userId != null, AgentAuditLog::getUserId, userId)
                .eq(StringUtils.hasText(sessionId), AgentAuditLog::getSessionId, sessionId)
                .eq(StringUtils.hasText(eventType), AgentAuditLog::getEventType, eventType)
                .ge(from != null, AgentAuditLog::getTs, from)
                .le(to != null, AgentAuditLog::getTs, to)
                .orderByDesc(AgentAuditLog::getTs);
        Page<AgentAuditLog> result = auditMapper.selectPage(new Page<>(Math.max(1, page), clampSize(size)), wrapper);
        return PageResult.of(result.getRecords(), result.getTotal(), result.getCurrent(), result.getSize());
    }

    private long clampSize(long size) {
        return Math.min(Math.max(1, size), 200);
    }

    // ---------------- 保留策略 ----------------

    /**
     * 清理超期审计日志（每日 03:17 执行；用非整点时刻，避免与其它定时任务/整点高峰撞车）。
     *
     * <p>{@code retention-days ≤ 0} 表示不清理。硬删除而非逻辑删除——见实体类注释。</p>
     */
    @Scheduled(cron = "0 17 3 * * ?")
    public void cleanupExpired() {
        int days = properties.getAudit().getRetentionDays();
        if (!properties.getAudit().isEnabled() || days <= 0) {
            return;
        }
        try {
            LocalDateTime deadline = LocalDateTime.now().minusDays(days);
            int removed = auditMapper.delete(new LambdaQueryWrapper<AgentAuditLog>()
                    .lt(AgentAuditLog::getTs, deadline));
            if (removed > 0) {
                log.info("审计日志保留策略：已清理 {} 条早于 {} 的记录", removed, deadline);
            }
        } catch (Exception e) {
            // 清理失败不影响写入与对话；下一天会重试
            log.warn("审计日志清理失败：{}", e.getMessage());
        }
    }

    // ---------------- 内部工具 ----------------

    private String toJson(Map<String, Object> args) {
        if (CollectionUtils.isEmpty(args)) {
            return null;
        }
        try {
            return truncate(objectMapper.writeValueAsString(args), MAX_ARGUMENTS_CHARS);
        } catch (Exception e) {
            return truncate(String.valueOf(args), MAX_ARGUMENTS_CHARS);
        }
    }

    /** 用户消息摘录：配置为 0 时完全不记正文（敏感信息最小化）。 */
    private String excerpt(String message) {
        int limit = properties.getAudit().getMessageExcerptChars();
        if (limit <= 0 || !StringUtils.hasText(message)) {
            return null;
        }
        return truncate(message.trim(), limit);
    }

    private static String truncate(String s, int max) {
        if (s == null || s.length() <= max) {
            return s;
        }
        return s.substring(0, max) + "…";
    }
}
