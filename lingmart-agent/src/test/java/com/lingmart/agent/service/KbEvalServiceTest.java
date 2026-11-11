package com.lingmart.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lingmart.agent.config.AgentProperties;
import com.lingmart.agent.dto.RetrievalResult;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.util.List;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 召回评估的纯逻辑单测：不连向量库、不调模型。
 *
 * <p>评估集本身的正确性是这类指标的前提——期望来源写错，算出来的 recall 就是假的，
 * 所以这里把「评估集可加载」「期望的文件真实存在」也当作被测行为。</p>
 */
class KbEvalServiceTest {

    /** 只用于读取评估集与配置默认值；knowledgeBaseService 传 null 不影响这些用例。 */
    private final KbEvalService service =
            new KbEvalService(null, new AgentProperties(), new ObjectMapper());

    private static KbEvalService.GoldenItem item(String question, String... expect) {
        return new KbEvalService.GoldenItem(question, List.of(expect));
    }

    // ---------------- 评估集本身 ----------------

    @Test
    void 评估集应可加载且非空() {
        List<KbEvalService.GoldenItem> items = service.loadGoldenSet();
        assertFalse(items.isEmpty(), "评估集不应为空");
        items.forEach(i -> {
            assertTrue(i.question() != null && !i.question().isBlank());
            assertFalse(i.expect().isEmpty());
        });
    }

    @Test
    void 评估集里期望的来源文件都应真实存在() {
        for (KbEvalService.GoldenItem i : service.loadGoldenSet()) {
            for (String file : i.expect()) {
                assertTrue(new ClassPathResource("kb/" + file).exists(),
                        "评估集引用了不存在的知识文件：" + file);
            }
        }
    }

    // ---------------- 命中判定 ----------------

    @Test
    void 命中任意一个期望来源即算命中() {
        var items = List.of(item("怎么退款", "05-refund-guide.md", "12-platform-limits.md"));
        Function<String, RetrievalResult> retriever = q ->
                new RetrievalResult("正文", List.of("12-platform-limits.md", "04-chat-faq.md"), 0.81, 2);

        var report = KbEvalService.evaluateItems(items, 3, retriever);

        assertEquals(1, report.hits());
        assertEquals(1.0, report.recall(), 1e-9);
        assertEquals(1, report.items().get(0).rank(), "12-platform-limits.md 排在第一位");
        assertTrue(report.misses().isEmpty());
    }

    @Test
    void 来源对不上算未命中并进入misses() {
        var items = List.of(item("怎么退款", "05-refund-guide.md"));
        var report = KbEvalService.evaluateItems(items, 3,
                q -> new RetrievalResult("正文", List.of("04-chat-faq.md"), 0.42, 1));

        assertEquals(0, report.hits());
        assertEquals(0.0, report.recall(), 1e-9);
        assertEquals(1, report.misses().size());
        assertNull(report.misses().get(0).rank());
        assertFalse(report.items().get(0).hit());
        // 有召回但召回了错的：不计入 emptyCount
        assertEquals(0, report.emptyCount());
    }

    @Test
    void 一条都没召回时单独计数() {
        var items = List.of(item("怎么退款", "05-refund-guide.md"));
        var report = KbEvalService.evaluateItems(items, 3, q -> RetrievalResult.EMPTY);

        assertEquals(0, report.hits());
        assertEquals(1, report.emptyCount());
        assertEquals(1, report.misses().size());
    }

    @Test
    void 排名取命中来源在列表中的位置() {
        var items = List.of(item("q", "z.md"));
        var report = KbEvalService.evaluateItems(items, 5,
                q -> new RetrievalResult("t", List.of("a.md", "b.md", "z.md"), 0.5, 3));

        assertEquals(3, report.items().get(0).rank());
    }

    @Test
    void 空评估集不应除零() {
        var report = KbEvalService.evaluateItems(List.of(), 3, q -> RetrievalResult.EMPTY);

        assertEquals(0, report.total());
        assertEquals(0.0, report.recall(), 1e-9);
    }

    @Test
    void 多个问题按命中比例计算召回率() {
        var items = List.of(
                item("问题一", "a.md"),
                item("问题二", "b.md"),
                item("问题三", "c.md"),
                item("问题四", "d.md"));
        Function<String, RetrievalResult> retriever = q ->
                "问题一".equals(q) || "问题三".equals(q)
                        ? new RetrievalResult("t", List.of(q.equals("问题一") ? "a.md" : "c.md"), 0.7, 1)
                        : new RetrievalResult("t", List.of("x.md"), 0.3, 1);

        var report = KbEvalService.evaluateItems(items, 3, retriever);

        assertEquals(4, report.total());
        assertEquals(2, report.hits());
        assertEquals(0.5, report.recall(), 1e-9);
        assertEquals(2, report.misses().size());
    }
}
