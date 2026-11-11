package com.lingmart.agent.service;

import com.lingmart.agent.config.AgentProperties;
import com.lingmart.agent.dto.RetrievalResult;
import com.lingmart.common.exception.BizException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.DigestUtils;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 知识库(RAG)服务：把内置 {@code classpath:kb/**} 与可配本地目录的 Markdown 切块、向量化存入
 * ChromaDB，在线检索 Top-K 相似段落供 System Prompt 引用。
 *
 * <p>降级原则：向量库不可达 / 检索抛错时，检索返回空并告警，对话照常（不让中间件故障拖垮问答）。</p>
 */
@Slf4j
@Service
public class KnowledgeBaseService {

    private static final String IDS_KEY = "agent:kb:ids";
    private static final String READY_KEY = "agent:kb:ready";
    /** 重叠去重拼接允许的最小公共边界长度（低于此按普通片段拼接，避免把无关文档误并） */
    private static final int MIN_MERGE_OVERLAP = 20;

    private final VectorStore vectorStore;
    private final AgentProperties properties;
    private final StringRedisTemplate redis;
    private final TokenTextSplitter splitter;

    public KnowledgeBaseService(ObjectProvider<VectorStore> vectorStoreProvider, AgentProperties properties,
                                StringRedisTemplate redis) {
        this.vectorStore = vectorStoreProvider.getIfAvailable();
        this.properties = properties;
        this.redis = redis;
        int chunk = Math.max(50, properties.getKb().getChunkSize());
        this.splitter = TokenTextSplitter.builder()
                .withChunkSize(chunk)
                .withMinChunkSizeChars(100)
                .withMinChunkLengthToEmbed(10)
                .withMaxNumChunks(100000)
                .withKeepSeparator(true)
                .build();
    }

    /** 知识库是否可用（开关 + 向量库 Bean 存在）。 */
    public boolean available() {
        return properties.getKb().isEnabled() && vectorStore != null;
    }

    /**
     * 重建/增量刷新知识库：内置 kb 资源 + 可配额外目录 → 切块 → 稳定 ID 幂等 Upsert，
     * 并清理已消失来源的旧分块。成功后置就绪标记。
     *
     * @return 入库分块总数
     */
    public long rebuild() {
        if (!available()) {
            throw new BizException("知识库不可用：lingmart.agent.kb.enabled=false 或未配置向量库");
        }
        redis.delete(READY_KEY);
        List<Document> chunks = loadChunks();
        Set<String> newIds = chunks.stream().map(Document::getId).collect(Collectors.toSet());
        try {
            Set<String> oldIds = readOldIds();
            Set<String> stale = new HashSet<>(oldIds);
            stale.removeAll(newIds);
            if (!stale.isEmpty()) {
                vectorStore.delete(new ArrayList<>(stale));
            }
            addInBatches(chunks);
            saveIds(newIds);
            redis.opsForValue().set(READY_KEY, String.valueOf(chunks.size()));
            log.info("Agent 知识库重建完成：新增/刷新分块 {} 条，清理 {} 条", chunks.size(), stale.size());
            return chunks.size();
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            // 刻意不再删除 IDS_KEY：它记录「上一次成功构建过哪些分块」，是下次重建计算"陈旧分块"的唯一依据。
            // 分批写入中途失败会在向量库留下部分数据，删掉 IDS_KEY 反而让下次重建无从清理这些残留
            // （且这些残留 ID 已不在 newIds 中，留着它们才能被正确识别为陈旧并删除）。
            // 就绪标记 READY_KEY 已在方法开头删除、此处不写回，所以失败仍如实体现为 ready:false。
            throw new BizException("知识库写入向量库失败：" + e.getMessage());
        }
    }

    /**
     * 分批写入向量库（每批不超过 {@code kb.embed-batch-size}，默认 10）。
     *
     * <p><b>为什么必须自己分批</b>：嵌入服务商对单次请求的<b>输入条数</b>有硬上限
     * （阿里云百炼 text-embedding-v3 为 10 条），超出直接返回
     * {@code batch size is invalid, it should not be larger than 10}。
     * Spring AI 的默认分批策略是按 <b>token 数</b>分批、不按条目数，管不到这个上限——
     * 语料一多（超过 10 块）整次重建就会失败，且失败只体现为 {@code ready:false}，不报错不告警。</p>
     *
     * <p>分批写入非原子：中途失败时前几批已入向量库。由于分块 ID 是「来源+内容哈希」的稳定 ID
     * 且写入是 Upsert，重新执行一次重建即可幂等收敛——所以这里不做回滚。</p>
     */
    private void addInBatches(List<Document> chunks) {
        if (chunks.isEmpty()) {
            return;
        }
        int batchSize = Math.max(1, properties.getKb().getEmbedBatchSize());
        int total = chunks.size();
        for (int from = 0; from < total; from += batchSize) {
            int to = Math.min(total, from + batchSize);
            vectorStore.add(chunks.subList(from, to));
            log.debug("知识库写入进度：{}/{}", to, total);
        }
    }

    /**
     * 检索与 query 最相关的知识段落，失败时降级返回空。
     *
     * <p>保留这个方法是为了不改动对话主链路的调用形态；需要"命中了哪几篇"时用
     * {@link #retrieve(String)}。</p>
     */
    public List<String> search(String query) {
        RetrievalResult result = retrieve(query);
        return result.isEmpty() ? List.of() : List.of(result.text());
    }

    /** 按配置的 Top-K 检索，返回正文与命中来源。 */
    public RetrievalResult retrieve(String query) {
        return retrieve(query, properties.getKb().getTopK());
    }

    /**
     * 按指定 Top-K 检索，返回正文与命中来源。
     *
     * <p><b>埋点约定</b>：成功路径也打一行 INFO，记 query 长度、命中块数与来源文件名、最高相似度；
     * <b>不记用户原文</b>——沿用"不把对话正文写进日志"的既有约定。没有这行日志，
     * "这轮为什么答错了/为什么像在编"就只能靠猜，召回率也无从统计。</p>
     *
     * @param topK 返回的段落数（&lt;1 时按 1 处理）
     */
    public RetrievalResult retrieve(String query, int topK) {
        if (!available()) {
            return RetrievalResult.EMPTY;
        }
        int k = Math.max(1, topK);
        try {
            List<Document> docs = vectorStore.similaritySearch(
                    SearchRequest.builder().query(query).topK(k).build());
            if (docs == null || docs.isEmpty()) {
                log.info("知识检索：queryLen={} topK={} 命中 0 块", length(query), k);
                return RetrievalResult.EMPTY;
            }
            LinkedHashSet<String> sources = new LinkedHashSet<>();
            Double topScore = null;
            for (Document d : docs) {
                String name = sourceName(d.getMetadata().get("source"));
                if (!name.isEmpty()) {
                    sources.add(name);
                }
                // 分数可能为 null（部分向量库/检索实现不返回），不能当 0 参与比较
                Double score = d.getScore();
                if (score != null && (topScore == null || score > topScore)) {
                    topScore = score;
                }
            }
            String merged = mergeOverlapped(docs);
            log.info("知识检索：queryLen={} topK={} chunks={} sources={} topScore={}",
                    length(query), k, docs.size(), sources, topScore);
            return new RetrievalResult(merged == null ? "" : merged, List.copyOf(sources), topScore, docs.size());
        } catch (Exception e) {
            log.warn("Agent 知识检索失败，已降级为无知识问答：{}", e.getMessage());
            return RetrievalResult.EMPTY;
        }
    }

    private static int length(String s) {
        return s == null ? 0 : s.length();
    }

    /**
     * 从向量库返回的 {@code source} 元数据里取出文件名。
     *
     * <p>入库时 {@code source} 用的是 {@code Resource#getDescription()}，实际形如
     * {@code class path resource [kb/02-shop-guide.md]} 或 {@code file [/path/to/x.md]}，
     * 带路径与方括号，不能直接与评估集里的文件名比对。这里统一裁成 basename；
     * <b>不改写入侧的元数据</b>，这样已有向量库无需重建即可参与评估。</p>
     */
    static String sourceName(Object source) {
        if (source == null) {
            return "";
        }
        String s = String.valueOf(source).trim();
        int slash = Math.max(s.lastIndexOf('/'), s.lastIndexOf('\\'));
        String tail = slash >= 0 ? s.substring(slash + 1) : s;
        // 去掉描述串尾部的方括号/空白（如 "...md]"）
        while (!tail.isEmpty() && (tail.endsWith("]") || Character.isWhitespace(tail.charAt(tail.length() - 1)))) {
            tail = tail.substring(0, tail.length() - 1).trim();
        }
        return tail;
    }

    /**
     * 把命中片段按“相邻块共享的重叠尾/头”去重后拼成一段连续文本。
     *
     * <p>字符级重叠会让相邻两块共享最多 overlapChars 个字符；若 Top-K 恰好同时命中两块，
     * 直接 join 会向 Prompt 注入重复文本、浪费 Token。这里按“前块后缀 ∩ 后块前缀”的最长公共串
     * 消除重复（我们的重叠正是把上一块末尾并入下一块头部，故边界可精确还原）。</p>
     */
    private static String mergeOverlapped(List<Document> docs) {
        StringBuilder out = new StringBuilder();
        for (Document d : docs) {
            String t = d.getText();
            if (t == null) {
                continue;
            }
            t = t.trim();
            if (t.isEmpty()) {
                continue;
            }
            if (out.length() == 0) {
                out.append(t);
                continue;
            }
            int common = commonSuffixPrefix(out.toString(), t);
            if (common >= MIN_MERGE_OVERLAP) {
                out.append(t.substring(common)); // 重叠边界已在前块出现过，只续非重复部分
            } else {
                out.append("\n\n").append(t);
            }
        }
        return out.toString();
    }

    /** 返回 a 的后缀与 b 的前缀最长公共长度（上限 300，避免跨文档的长串被误当作重叠）。 */
    private static int commonSuffixPrefix(String a, String b) {
        int max = Math.min(a.length(), b.length());
        int cap = Math.min(max, 300);
        for (int k = cap; k >= 1; k--) {
            if (a.regionMatches(a.length() - k, b, 0, k)) {
                return k;
            }
        }
        return 0;
    }

    /** 就绪状态：是否已完成过一次成功重建。 */
    public boolean isReady() {
        return available() && Boolean.TRUE.equals(redis.hasKey(READY_KEY));
    }

    /** 当前知识库分块数（未构建/不可用时返回 -1）。 */
    public long chunkCount() {
        if (!available()) {
            return -1;
        }
        String v = redis.opsForValue().get(READY_KEY);
        if (v == null) {
            return -1;
        }
        try {
            return Long.parseLong(v);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    // ---------------- 内部：加载与切块 ----------------

    private List<Document> loadChunks() {
        List<Document> chunks = new ArrayList<>();
        for (Resource resource : listKnowledgeResources()) {
            try {
                String text = resource.getContentAsString(StandardCharsets.UTF_8);
                if (!StringUtils.hasText(text)) {
                    continue;
                }
                chunkAndCollect(resource, text, chunks);
            } catch (IOException e) {
                log.warn("读取知识源失败 {}: {}", resource.getDescription(), e.getMessage());
            }
        }
        return chunks;
    }

    private void chunkAndCollect(Resource resource, String text, List<Document> chunks) {
        String source = resource.getDescription();
        Document doc = new Document(text);
        // 字符级重叠：TokenTextSplitter 无 overlap API，用「上一块末尾 overlapChars 个字符并入下一块头部」
        // 近似重叠，缓解跨块边界的语义割裂（检索命中时块内含前后边界上下文）。
        int overlap = Math.max(0, properties.getKb().getOverlapChars());
        String prevTail = "";
        for (Document part : splitter.split(doc)) {
            String raw = part.getText();
            if (raw == null || raw.isBlank()) {
                prevTail = "";
                continue;
            }
            String content = prevTail.isEmpty() ? raw : prevTail + "\n" + raw;
            // 稳定 ID：source + 内容哈希 → 幂等 Upsert、可按来源清理
            String id = "kb-" + DigestUtils.md5DigestAsHex((source + "\n" + content).getBytes(StandardCharsets.UTF_8));
            chunks.add(new Document(id, content, java.util.Map.of("source", source)));
            prevTail = tail(raw, overlap);
        }
    }

    private static String tail(String text, int chars) {
        if (chars <= 0 || text.length() <= chars) {
            return "";
        }
        return text.substring(text.length() - chars);
    }

    private List<Resource> listKnowledgeResources() {
        List<Resource> result = new ArrayList<>();
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        try {
            result.addAll(List.of(resolver.getResources("classpath*:kb/**/*.md")));
        } catch (IOException e) {
            log.warn("扫描 classpath:kb/** 失败：{}", e.getMessage());
        }
        // 本地额外知识目录（仅本地运行生效）
        String extraDirs = properties.getKb().getExtraDirs();
        if (StringUtils.hasText(extraDirs)) {
            for (String dir : extraDirs.split(",")) {
                String trimmed = dir.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                try (Stream<Path> walk = Files.walk(Paths.get(trimmed))) {
                    walk.filter(p -> p.toString().toLowerCase().endsWith(".md")).forEach(p -> {
                        try {
                            result.add(new org.springframework.core.io.FileSystemResource(p));
                        } catch (Exception ignore) {
                            // 单文件加载失败忽略
                        }
                    });
                } catch (IOException e) {
                    log.warn("扫描额外知识目录失败 {}：{}", trimmed, e.getMessage());
                }
            }
        }
        return result;
    }

    private Set<String> readOldIds() {
        Set<String> old = redis.opsForSet().members(IDS_KEY);
        return old == null ? new HashSet<>() : new HashSet<>(old);
    }

    private void saveIds(Set<String> ids) {
        redis.delete(IDS_KEY);
        if (!ids.isEmpty()) {
            redis.opsForSet().add(IDS_KEY, ids.toArray(String[]::new));
        }
    }
}
