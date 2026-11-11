package com.lingmart.agent.dto;

import java.util.List;

/**
 * 一次知识检索的结果：合并后的正文 + 命中来源 + 相似度。
 *
 * <p><b>为什么要带来源</b>：原先 {@code search()} 只返回拼接后的文本，
 * {@code Document.metadata.source} 在 {@code mergeOverlapped} 里被丢弃——于是"这一轮到底命中了哪几篇"
 * 完全不可观测，召回率也就无从计算。这里把来源按检索排名保留下来，
 * 供日志埋点与 {@link com.lingmart.agent.service.KbEvalService 召回评估}共用。</p>
 *
 * @param text       合并去重后的正文（无命中时为空串，不会是 null）
 * @param sources    命中的来源文件名（按检索排名去重，顺序即相关度降序）
 * @param topScore   最高相似度；向量库未返回分数时为 {@code null}（不能当 0 处理）
 * @param chunkCount 命中的原始分块数（合并前），用于区分"命中 1 块"与"多块合并"
 */
public record RetrievalResult(String text, List<String> sources, Double topScore, int chunkCount) {

    /** 无命中/不可用时的统一返回值，避免各处返回 null。 */
    public static final RetrievalResult EMPTY = new RetrievalResult("", List.of(), null, 0);

    /** 是否没有召回到任何内容。 */
    public boolean isEmpty() {
        return text == null || text.isBlank();
    }
}
