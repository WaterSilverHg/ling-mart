package com.lingmart.agent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Agent 操作审计日志实体（对应表 {@code lingmart_agent.agent_audit_log}）。
 *
 * <p>append-only：只插入、只按时间硬删除（保留策略），不更新、不做逻辑删除。
 * 因此本实体<b>刻意没有 {@code deleted} 字段</b>——审计记录的"被删除"本身不该是一个可查询的软状态。</p>
 *
 * <p>敏感信息最小化：不存用户消息正文，只存定长摘录 {@link #messageExcerpt}（长度可配 0 关闭）。</p>
 */
@Data
@TableName("agent_audit_log")
public class AgentAuditLog {

    /** 主键（雪花，应用侧分配） */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 用户 ID（网关透传） */
    private Long userId;

    /** 会话 ID */
    private String sessionId;

    /** 事件类型（见 AgentAuditService 的 EVENT_* 常量） */
    private String eventType;

    /** 模型侧工具调用 ID */
    private String toolCallId;

    /** 工具名（navigate_to / click_element / prefill_shop_application；只读工具见 tool_run 事件） */
    private String toolName;

    /** 前端动作（navigate / click / prefill） */
    private String action;

    /** 工具参数 JSON（route / elementId / elementText 等） */
    private String arguments;

    /** 操作目的说明（确认卡片文案） */
    private String purpose;

    /** 用户动作：confirm / cancel */
    private String userAction;

    /** 结果：proposed / success / failure / rejected / text / tool / empty / ok / cache_hit / quota_exceeded / failed / blocked / reset */
    private String result;

    /** 失败原因或错误详情 */
    private String errorMsg;

    /** 用户消息摘录（定长截断；配置为 0 时不写） */
    private String messageExcerpt;

    /** 事件时间（毫秒精度，同轮内可排序） */
    private LocalDateTime ts;
}
