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
 * @param documentId  本<b>轮</b>随消息附上的文档（上传接口返回的 id，可为空）。
 *                    正文留在服务端，这里只传引用——正是为了让长清单不必挤进 {@code message}
 *                    再撞上 {@code max-message-length}（默认 2000 字）而发不出去。
 *                    服务端会用它与会话中现存文档比对，对不上就不注入（如卡片停留过久、
 *                    期间又传了别的文件）。
 */
public record AgentChatRequest(
        @Size(max = 2000, message = "消息过长") String message,
        String sessionId,
        PageContext context,
        List<ToolExecutionResult> toolResults,
        String documentId) {
}
