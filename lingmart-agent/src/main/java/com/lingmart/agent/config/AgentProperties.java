package com.lingmart.agent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 轻营 · Agent 服务自定义配置（前缀 {@code lingmart.agent}）。
 *
 * <p>承载应用层参数：会话记忆策略、知识库(RAG)开关与切分参数、输入约束与限流阈值。
 * 经 {@code @ConfigurationPropertiesScan} 自动注册为 Bean（见 {@code AgentApplication}）。</p>
 */
@Data
@ConfigurationProperties(prefix = "lingmart.agent")
public class AgentProperties {

    /** 会话记忆配置 */
    private Memory memory = new Memory();

    /** 操作审计日志配置 */
    private Audit audit = new Audit();

    /** 只读工具（服务端自主循环）配置 */
    private ReadOnly readOnly = new ReadOnly();

    /** 知识库(RAG)配置 */
    private Kb kb = new Kb();

    /** 用户消息最大字符数（防提示词注入 / 防超长上下文） */
    private int maxMessageLength = 2000;

    /** 拼给模型的「历史 + 当前消息」字符总预算：超出从最旧消息开始裁剪，
     *  防止恶意/长会话把上下文窗口撑爆（字符级近似 token，非精确计数） */
    private int maxContextChars = 30_000;

    /** 每用户每分钟最大请求数（Redis 分布式限流，防刷接口） */
    private int ratePerUserMinute = 10;

    /** 单个会话最多可执行的“页面操作”（确认后回执）次数，超出引导清空会话重来（防无限确认/循环） */
    private int maxToolIterations = 20;

    /** 单轮最多下发给用户确认的「页面操作」数量（默认 1）。
     *  模型一次请求多个时只保留前 N 个，其余丢弃并记入违规提示带到下一轮。
     *  默认 1 是为了让用户每轮只面对一张确认卡片——多张卡并列时，攻击者可以在
     *  正常动作中间夹带一个恶意动作，而用户会习惯性挨个点确认。 */
    private int maxToolsPerTurn = 1;

    /** 单个会话最多保留的「未确认操作意图」条数（默认 5，超出丢最旧）。
     *  挂起意图采用追加语义（见 ChatMemoryService.appendPending），用户长期不确认时会累积，
     *  故设上限兜底；上限过小会让用户回头点旧卡片时匹配不到下发记录。 */
    private int maxPendingActions = 5;

    /** 单用户最多保留的会话数（默认 50，超出淘汰最久未活跃的会话及其全部数据）。
     *  sessionId 由前端自由提供，若无上限，一个用户可无限创建会话、把 Redis 撑大。 */
    private int maxSessionsPerUser = 50;

    /** SSE 处理最大并发数（信号量兜底，超出快速返回 503），防恶意流量打满虚拟线程与 LLM API */
    private int maxConcurrent = 32;

    /** 打字机逐字渲染间隔毫秒：0=实时输出不做人为延迟（当前为阻塞生成后分块回放，0 即一次性吐出） */
    private int typingDelayMs = 0;

    /** 单次 LLM 调用超时（毫秒），超时友好降级提示而非无限等待 */
    private int llmTimeoutMs = 30_000;

    /**
     * 会话记忆：Redis 存储、TTL 自动过期、条数上限裁剪。
     */
    @Data
    public static class Memory {
        /** 单会话最多保留消息条数（超出丢最旧） */
        private int maxMessages = 20;
        /** 会话保留时长（小时） */
        private int ttlHours = 24;
    }

    /**
     * 操作审计日志：落 {@code lingmart_agent.agent_audit_log}，用于事后追溯
     * 「AI 建议了什么、用户确认了什么、结果如何」。
     */
    @Data
    public static class Audit {
        /** 是否启用审计（关闭则完全不写库；用于本地纯调试） */
        private boolean enabled = true;
        /** 保留天数，超期由定时任务硬删除；≤0 表示不清理（永不删） */
        private int retentionDays = 30;
        /**
         * 用户消息摘录长度（字符）。审计要能还原"用户问了什么"，但又不能把对话正文落库，
         * 故只存定长摘录；设为 <b>0</b> 则完全不记正文（敏感信息最小化最严档）。
         */
        private int messageExcerptChars = 80;
    }

    /**
     * 只读工具（服务端自主循环）。
     *
     * <p>只读工具免用户确认、由服务端直接执行——因此必须成套设置闸门，否则「免确认」会变成
     * 「无界调用 + 无界数据入上下文」。三类闸门：单轮次数、单轮时长、单会话累计次数。</p>
     */
    @Data
    public static class ReadOnly {
        /** 是否启用只读工具（关闭后模型看不到这些工具，退化为纯问答 + 页面操作引导） */
        private boolean enabled = true;
        /** 单轮内最多执行的只读工具次数（含中间轮），超出即停止循环用已有信息作答 */
        private int maxCallsPerTurn = 5;
        /**
         * 单轮自主循环的总时长预算（毫秒）。默认 15s——
         * 一次工具调用 ≈ 1 次 LLM 往返(1~3s) + 1 次 HTTP，3 次连查 ≈ 7.5s，再加最终作答 ≈ 10s+，
         * 故预算需 ≥ 15s 才能达成"一轮连查 2~3 个数据"的目标（早期设 8s 会在达成前就掐断）。
         */
        private long turnBudgetMs = 15_000;
        /** 单会话累计只读工具调用上限（防被诱导连续查询形成爬取） */
        private int maxCallsPerSession = 50;
        /** 只读工具结果的会话内短缓存秒数（同会话同工具同参数不重复请求） */
        private int cacheTtlSeconds = 30;
        /** 工具返回文本的字符上限（超出截断并注明未展示条数） */
        private int maxResultChars = 1500;
        /** 网关公网地址：只读工具经网关调用业务 API（案 B：Agent 只做转发者，不成为权限判断者） */
        private String gatewayBaseUrl = "http://localhost:88";
    }

    /**
     * 知识库(RAG)：ChromaDB 向量检索。
     */
    @Data
    public static class Kb {
        /** 是否启用知识检索（关闭则纯对话，不连向量库） */
        private boolean enabled = true;
        /**
         * 启动时若知识库为空则后台重建。
         *
         * <p><b>实际默认值以 {@code application.yml} 为准</b>（{@code AGENT_KB_REBUILD_ON_START}，默认 true）——
         * 那里的绑定会覆盖此处的字段初值。默认为 true 是为了新环境"第一次"就能基于知识库回答，
         * 否则要先手工调管理员端点重建；重建在后台虚拟线程执行，失败只告警不阻断启动。
         * 若该值与 {@code .env} 冲突，以 {@code .env} 为准。</p>
         */
        private boolean rebuildOnStart = false;
        /** 相似度检索返回段落数 Top-K */
        private int topK = 3;
        /** 文本切块目标大小（token） */
        private int chunkSize = 400;
        /**
         * 单次嵌入请求的最大输入条数（默认 10）。
         *
         * <p><b>不可随意调大</b>：阿里云百炼 text-embedding-v3 单次请求最多接受 10 条输入，
         * 超出直接返回 400 {@code batch size is invalid, it should not be larger than 10}。
         * Spring AI 的默认分批策略是按 <b>token 数</b>分批（不按条目数），管不到这个上限，
         * 因此由 KnowledgeBaseService 在调用向量库前自行按本值切批。</p>
         */
        private int embedBatchSize = 10;
        /** 相邻切块字符级重叠长度（TokenTextSplitter 无 overlap API，用“上一块末尾字符并入下一块头”近似，提升边界语义连贯）。
         *  权衡冗余与边界已调低默认；检索结果会做「重叠去重拼接」（见 KnowledgeBaseService.mergeOverlapped），
         *  故本值主要影响向量质量/索引冗余，不再造成送入上下文的重复文本。 */
        private int overlapChars = 60;
        /** 额外知识源目录（逗号分隔的本地绝对路径，仅本地运行有效；镜像内用内置 classpath:kb/**） */
        private String extraDirs = "";
    }
}
