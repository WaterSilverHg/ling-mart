package com.lingmart.agent.dto;

import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 前端 → Agent 的单轮对话请求体（经网关 POST /api/agent/chat，SSE 响应）。
 *
 * <p>userId / userRole <b>不入请求体</b>（否则调用方可任意指定"我是谁"），而是本服务从
 * {@code Authorization} 头自行验签 JWT 解析得到（见 {@code JwtAuthService#resolve}）。</p>
 *
 * @param message     用户输入的文字（若携带 toolResults 允许为空：纯操作回执续跑）
 * @param sessionId   会话标识（前端生成 UUID，本地持久化，页面刷新可恢复）
 * @param context     当前页面上下文（路由/标题/可见文本/可交互元素）
 * @param toolResults 上一轮「操作预览卡片」经用户确认/取消后的执行回执（可为空）
 */
public record AgentChatRequest(
        @Size(max = 2000, message = "消息过长") String message,
        String sessionId,
        PageContext context,
        List<ToolExecutionResult> toolResults) {
}
