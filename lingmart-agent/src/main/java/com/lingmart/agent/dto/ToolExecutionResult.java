package com.lingmart.agent.dto;

/**
 * 一次「操作预览卡片」的执行回执：用户在前端点击【确认】/【取消】并（若确认）由前端
 * DOM 执行器完成实际操作后，作为下一轮对话的一部分送回 Agent，供其继续推理。
 *
 * <p><b>回执不被信任</b>：它由前端构造、客户端可任意篡改，因此服务端会用 {@code nonce} 比对
 * 「本轮下发过的挂起意图」，未命中的回执一律不采信、不得当作已发生的操作
 * （校验与消费见 {@code ChatMemoryService.consumeReceipts}）。</p>
 *
 * @param action  实际操作动作（navigate / click / fill / scroll）
 * @param purpose 操作目的说明（Agent 发起操作时的原话）
 * @param success 是否执行成功（取消视为 false）
 * @param message 结果/错误详情（成功时如「已导航到申请开店页面」，失败/取消时说明原因）
 * @param nonce   服务端下发该操作意图时生成的一次性校验串，前端须原样回传。
 *                刻意不放进模型可见的上下文，故模型无法据此伪造回执。
 * @param outcome 用户动作：{@code confirm}（点了确认）/ {@code cancel}（点了取消）。
 *                显式字段而非从 {@code success} 推断——"用户主动取消"与"用户确认但执行失败"
 *                都是 {@code success=false}，靠文案区分不可靠，而审计必须能分开统计。
 */
public record ToolExecutionResult(
        String action,
        String purpose,
        boolean success,
        String message,
        String nonce,
        String outcome) {
}
