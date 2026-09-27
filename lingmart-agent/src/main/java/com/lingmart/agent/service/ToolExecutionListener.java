package com.lingmart.agent.service;

import java.time.LocalDateTime;

/**
 * 只读工具执行的进度回调。
 *
 * <p>只读工具由服务端自主执行、没有"用户点确认"这个环节，所以用户唯一的感知来源就是这两个事件——
 * 没有它，用户会看到浮窗卡住几秒然后突然冒出一段答案，不知道发生过什么。</p>
 *
 * <p>{@code tool_run} 在发起请求<b>前</b>回调（前端可显示"正在查询你的订单…"）；
 * {@code tool_result} 在拿到结果<b>后</b>回调（前端替换成结果卡片）。</p>
 *
 * <p>实现方（通常是控制器）负责把回调转成 SSE 事件；<b>回调不得抛异常打断循环</b>，
 * 客户端断开的处理应由实现方自行兜住。</p>
 */
public interface ToolExecutionListener {

    /**
     * 只读工具开始执行。
     *
     * @param toolCallId 模型侧工具调用 ID（前端据此把 run 与 result 关联成同一张卡片）
     * @param toolName   工具名
     * @param label      中文展示标签（如"查询我的订单"）
     */
    void onToolRun(String toolCallId, String toolName, String label);

    /**
     * 只读工具执行完毕。
     *
     * @param summary  给用户看的一句话摘要（如「【我的订单】筛选状态：待支付；共 3 条」）
     * @param dataAsOf 数据时间戳；缓存命中时是<b>首次查询</b>的时间（不是当前时间），
     *                 前端必须展示它——否则用户回看历史会以为是实时数据
     */
    void onToolResult(String toolCallId, String toolName, String summary, LocalDateTime dataAsOf);

    /** 空实现：不需要进度回调时使用（如内部调用、测试）。 */
    ToolExecutionListener NOOP = new ToolExecutionListener() {
        @Override
        public void onToolRun(String toolCallId, String toolName, String label) {
            // 无进度需求
        }

        @Override
        public void onToolResult(String toolCallId, String toolName, String summary, LocalDateTime dataAsOf) {
            // 无进度需求
        }
    };
}
