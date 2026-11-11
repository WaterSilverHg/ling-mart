package com.lingmart.agent.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lingmart.agent.config.AgentProperties;
import com.lingmart.agent.dto.RetrievalResult;
import com.lingmart.common.exception.BizException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * 知识库召回评估：用一份带"期望来源"的评估集跑真实检索链路，算出 recall@K。
 *
 * <p><b>为什么需要它</b>：在此之前召回质量只能靠人工抽查（见 agent-copilot §9.5），
 * 任何检索优化（调 Top-K、加阈值、查询改写）都拿不出"改之前 vs 改之后"的数字。
 * 评估只走<b>检索层</b>，不调用 LLM——所以既便宜、又可重复，结果不受模型随机性影响。</p>
 *
 * <p><b>口径</b>：一条问题算"命中"，当且仅当它期望的来源文件出现在 Top-K 命中来源里
 * （多来源时命中任意一个即可）。这与"模型最终答对"不是一回事——后者还受提示词、模型能力影响，
 * 这里只衡量"该看到的资料有没有被捞上来"。</p>
 */
@Slf4j
@Service
public class KbEvalService {

    /** 评估集位置（classpath）。放在 kb/ 之外，避免被知识库扫描当成语料。 */
    static final String GOLDEN_SET_PATH = "kb-eval/golden-set.json";

    private final KnowledgeBaseService knowledgeBaseService;
    private final AgentProperties properties;
    private final ObjectMapper objectMapper;

    public KbEvalService(KnowledgeBaseService knowledgeBaseService, AgentProperties properties,
                         ObjectMapper objectMapper) {
        this.knowledgeBaseService = knowledgeBaseService;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /**
     * 跑一轮完整评估。
     *
     * @param topK 检索段落数；为空时用当前配置值（{@code lingmart.agent.kb.top-k}）
     * @return 汇总 + 每条明细（含未命中项，便于定位是哪些问题漏了）
     */
    public EvalReport evaluate(Integer topK) {
        if (!knowledgeBaseService.available()) {
            // 知识库被显式关闭 / 没有向量库 Bean：这不是"召回差"，是没配好，直接说清楚
            throw new BizException("知识库不可用：lingmart.agent.kb.enabled=false 或未配置向量库");
        }
        List<GoldenItem> items = loadGoldenSet();
        int effectiveTopK = topK == null
                ? Math.max(1, properties.getKb().getTopK())
                : Math.max(1, topK);
        Function<String, RetrievalResult> retriever = q -> knowledgeBaseService.retrieve(q, effectiveTopK);
        EvalReport report = evaluateItems(items, effectiveTopK, retriever);
        log.info("知识库召回评估：topK={} total={} hits={} recall={} empty={}",
                report.topK(), report.total(), report.hits(), report.recall(), report.emptyCount());
        return report;
    }

    /** 读取并校验评估集。校验失败直接抛：一份写错的评估集比没有更糟（会给出假数字）。 */
    public List<GoldenItem> loadGoldenSet() {
        ClassPathResource resource = new ClassPathResource(GOLDEN_SET_PATH);
        try (InputStream in = resource.getInputStream()) {
            List<GoldenItem> items = objectMapper.readValue(in, new TypeReference<List<GoldenItem>>() {
            });
            if (items == null || items.isEmpty()) {
                throw new BizException("评估集为空：" + GOLDEN_SET_PATH);
            }
            for (GoldenItem item : items) {
                if (item == null || !StringUtils.hasText(item.question())
                        || item.expect() == null || item.expect().isEmpty()) {
                    throw new BizException("评估集条目缺少 question 或 expect：" + GOLDEN_SET_PATH);
                }
            }
            return items;
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException("评估集加载失败：" + e.getMessage());
        }
    }

    /**
     * 纯函数形式的评估核心：把"怎么检索"外置成函数，便于单测（不依赖向量库）。
     *
     * @param items     评估集
     * @param topK      本次使用的 Top-K（仅用于回填报告）
     * @param retriever 问题 → 检索结果
     */
    static EvalReport evaluateItems(List<GoldenItem> items, int topK,
                                    Function<String, RetrievalResult> retriever) {
        List<ItemResult> results = new ArrayList<>(items.size());
        int hits = 0;
        int empty = 0;
        for (GoldenItem item : items) {
            RetrievalResult result = retriever.apply(item.question());
            if (result == null) {
                result = RetrievalResult.EMPTY;
            }
            if (result.isEmpty()) {
                empty++;
            }
            Integer rank = firstMatchRank(result.sources(), item.expect());
            if (rank != null) {
                hits++;
            }
            results.add(new ItemResult(item.question(), item.expect(), result.sources(),
                    rank != null, rank, result.topScore()));
        }
        double recall = items.isEmpty() ? 0 : (double) hits / items.size();
        List<ItemResult> misses = results.stream().filter(r -> !r.hit()).toList();
        return new EvalReport(topK, items.size(), hits, recall, empty, misses, results);
    }

    /**
     * 期望来源在命中列表中的 1-based 排名；未命中返回 {@code null}。
     *
     * <p>排名是为了将来能算 MRR（命中得越靠前越好）——现在先记录，不必立刻用。</p>
     */
    static Integer firstMatchRank(List<String> retrieved, List<String> expect) {
        if (retrieved == null || expect == null) {
            return null;
        }
        for (int i = 0; i < retrieved.size(); i++) {
            String got = retrieved.get(i);
            for (String want : expect) {
                if (want != null && want.equalsIgnoreCase(got)) {
                    return i + 1;
                }
            }
        }
        return null;
    }

    /** 评估集条目：一个问题 + 一个或多个可接受的期望来源文件名。 */
    public record GoldenItem(String question, List<String> expect) {
    }

    /**
     * 评估报告。
     *
     * @param topK      本次使用的 Top-K
     * @param total     题目总数
     * @param hits      命中题数
     * @param recall    recall@K（hits / total，0~1）
     * @param emptyCount 一条都没召回的题数（比"命中但来源不对"更需要优先处理）
     * @param misses    未命中明细
     * @param items     全部明细
     */
    public record EvalReport(int topK, int total, int hits, double recall, int emptyCount,
                             List<ItemResult> misses, List<ItemResult> items) {
    }

    /** 单题结果。 */
    public record ItemResult(String question, List<String> expect, List<String> retrieved,
                             boolean hit, Integer rank, Double topScore) {
    }
}
