package com.lingmart.agent.dto;

import java.util.Map;

/**
 * 模型请求执行的一个「页面操作意图」（已脱敏为前端可执行的描述，服务端不代执行）。
 * 经 SSE {@code tool_call} 事件下发给前端渲染为操作预览卡片，同时作为「挂起意图」存入 Redis。
 *
 * @param toolCallId 模型侧的工具调用 ID（前端卡片标识用；<b>不能</b>用作回执校验，
 *                   它由模型生成、可预测，故另有服务端生成的 {@code nonce}）
 * @param name       工具名（navigate_to / click_element）
 * @param action     前端 DOM 执行器动作（navigate / click）
 * @param arguments  工具参数（navigate→route/purpose；click→elementId/purpose/elementText）
 * @param purpose    操作目的说明（确认卡片文案）
 * @param nonce      服务端生成的一次性校验串：随 SSE 下发、由前端在回执中原样带回，
 *                   用于确认该回执确实对应当前挂起的意图。不进入模型可见的上下文。
 */
public record AgentToolCall(
        String toolCallId,
        String name,
        String action,
        Map<String, Object> arguments,
        String purpose,
        String nonce) {
}
