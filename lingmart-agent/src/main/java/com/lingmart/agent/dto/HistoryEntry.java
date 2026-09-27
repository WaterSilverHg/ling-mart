package com.lingmart.agent.dto;

/**
 * 一条会话记忆（Redis 中以 JSON 行存储）。
 *
 * @param role    角色：user / assistant
 * @param content 文本内容
 * @param ts      时间戳（毫秒）
 */
public record HistoryEntry(
        String role,
        String content,
        long ts) {
}
