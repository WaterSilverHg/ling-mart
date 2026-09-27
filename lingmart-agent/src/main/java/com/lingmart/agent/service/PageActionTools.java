package com.lingmart.agent.service;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * Agent 暴露给 LLM 的「页面操作」工具集。
 *
 * <p>这些工具<b>不会被服务端执行</b>：AgentTurnService 以
 * {@code internalToolExecutionEnabled(false)} 调用模型——模型只能“请求”工具，返回的工具调用
 * 会被包装为 SSE tool_call 事件下发给前端浮窗，经用户点击确认后由前端 DOM 执行器真正执行。
 * 工具的方法体仅为兜底占位（理论上不会走到），返回值为“操作意图描述”，不得包含副作用。</p>
 */
public class PageActionTools {

    /**
     * 使用 Vue Router 导航到平台内某个页面路由。
     *
     * @param route   目标路由路径（如 /shop/apply、/orders、/seller），必须是以 / 开头的平台真实存在的路由
     * @param purpose 向用户展示的操作目的说明
     * @return 操作意图描述（不会真正执行）
     */
    @Tool(name = "navigate_to", description = "让用户跳转到平台内的某个页面路由（经用户确认后由前端执行导航）。"
            + "适用于“我要去申请开店 / 看我的订单 / 到卖家中心”这类导航需求。")
    public String navigateTo(
            @ToolParam(description = "目标路由路径，必须是 / 开头的平台真实存在的路由，如 /shop/apply") String route,
            @ToolParam(description = "给用户看的操作目的，一句话说明为什么跳转") String purpose) {
        return "操作意图[跳转页面] route=" + route + " purpose=" + purpose + "（等待用户确认）";
    }

    /**
     * 点击页面上的某个按钮或链接（经用户确认后由前端 DOM 执行器触发）。
     *
     * @param elementId   元素 ID，必须来自页面上下文提供的可交互元素列表（形如 {@code c12}）
     * @param purpose      向用户展示的操作目的说明
     * @param elementText  该元素当前的可见文本（取自页面可交互元素列表的 text，可为空）；前端执行前会据此
     *                     校验目标内容是否已变——ID 保证点的是同一个**节点**，但不保证它**内容没变**
     *                     （例如某行数据被替换后节点复用），那正是最典型的误点
     * @return 操作意图描述（不会真正执行）
     */
    @Tool(name = "click_element", description = "点击当前页面上的某个按钮或链接（经用户确认后由前端执行点击）。"
            + "适用于提交表单、打开某个功能入口等点击需求；elementId 只能取自已上报的可交互元素列表。")
    public String clickElement(
            @ToolParam(description = "元素 ID，取自页面可交互元素列表的 id 字段，形如 c12") String elementId,
            @ToolParam(description = "给用户看的操作目的，一句话说明这次点击要完成什么") String purpose,
            @ToolParam(description = "该元素当前的可见文本（取自页面可交互元素列表中的 text，可为空），"
                    + "用于前端执行前核对目标内容是否已变化，避免点到更新后的错误行", required = false) String elementText) {
        return "操作意图[点击元素] elementId=" + elementId + " purpose=" + purpose
                + (elementText == null ? "" : " elementText=" + elementText) + "（等待用户确认）";
    }
}
