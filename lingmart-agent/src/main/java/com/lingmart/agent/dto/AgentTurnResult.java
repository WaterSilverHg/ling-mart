package com.lingmart.agent.dto;

import java.util.List;

/**
 * 单轮 LLM 推理结果：要么是纯文字回答，要么是一个或多个待用户确认的页面操作意图。
 *
 * @param kind  TEXT=成文回答；TOOL=发起操作意图；EMPTY=模型无输出（容错）
 * @param text  成文回答内容（kind=TEXT 时有值）
 * @param tools 待确认的操作意图列表（kind=TOOL 时有值）
 */
public record AgentTurnResult(
        Kind kind,
        String text,
        List<AgentToolCall> tools) {

    public enum Kind {
        TEXT,
        TOOL,
        EMPTY
    }

    public static AgentTurnResult text(String text) {
        return new AgentTurnResult(Kind.TEXT, text, null);
    }

    public static AgentTurnResult tools(List<AgentToolCall> tools) {
        return new AgentTurnResult(Kind.TOOL, null, tools);
    }

    /** 工具结果可附带一段先展示的引导语（模型常同时给出短句 + 工具调用）。 */
    public static AgentTurnResult toolsWithText(String text, List<AgentToolCall> tools) {
        return new AgentTurnResult(Kind.TOOL, text, tools);
    }

    public static AgentTurnResult empty() {
        return new AgentTurnResult(Kind.EMPTY, null, null);
    }
}
