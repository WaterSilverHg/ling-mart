package com.lingmart.agent.dto;

import java.util.List;

/**
 * 页面上下文快照：前端浮窗采集、随每轮对话发送，供 Agent 给出与当前场景相关的建议，
 * 并为 click_element 工具提供可复用的 CSS 选择器。
 *
 * @param route        当前路由路径（如 /shop/apply）
 * @param title        页面标题
 * @param visibleText  可见文本摘要（去重压缩后的片段，非全文）
 * @param elements     当前页可交互元素列表
 */
public record PageContext(
        String route,
        String title,
        String visibleText,
        List<InteractiveElement> elements) {
}
