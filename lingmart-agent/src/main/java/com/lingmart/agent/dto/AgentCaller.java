package com.lingmart.agent.dto;

/**
 * 本轮请求的调用者身份（由 Agent 服务<b>自己校验 JWT</b> 得出，非取自网关透传头）。
 *
 * @param userId   用户 ID（JWT subject）
 * @param userRole 用户角色（JWT role 声明）
 * @param token    原始 JWT 字符串。只读工具经网关调用业务 API 时<b>原样透传</b>它——
 *                 鉴权逻辑因此仍只有网关 + 业务服务一份，Agent 只是转发者。
 *                 <b>不得写入日志、不得放进模型可见的上下文。</b>
 */
public record AgentCaller(
        Long userId,
        String userRole,
        String token) {
}
