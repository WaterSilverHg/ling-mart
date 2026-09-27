package com.lingmart.agent.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lingmart.agent.config.AgentProperties;
import com.lingmart.agent.dto.AgentCaller;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 只读工具基类：把「会话缓存 → 会话配额 → 经网关取数 → 渲染 → 回写缓存 → 审计」这条
 * 每个只读工具都要走的流水线收敛到一处。
 *
 * <p><b>为什么必须收敛</b>：缓存与配额是「免用户确认」的代价——只读工具不经用户点头就能打业务接口，
 * 两道闸门就是它唯一的刹车。三个工具各抄一份的话，将来改一处（例如让缓存命中也算一次配额）
 * 就会漏改另外两处，而漏掉的那处<b>不会报编译错误、也不会让任何测试失败</b>，
 * 只会静默地少一道闸门。安全控制不能复制粘贴。</p>
 *
 * <p><b>每轮实例化</b>：子类持有本轮调用者的身份与 token、以及会话 ID，都是请求级状态，
 * 不能做成单例 Bean——否则并发请求会互相串身份（最严重的后果是 A 的查询用 B 的 token 执行）。
 * 构造由 {@code AgentTurnService#buildToolCallbacks} 在每轮对话时完成。</p>
 *
 * <p><b>数据通路（案 B）</b>：一律经<b>网关</b>调用业务公开 API 并原样透传用户 JWT，
 * 由网关与业务服务完成鉴权。Agent 只是转发者，不成为新的权限判断点——
 * 就算被注入，它能做的也仅是"替用户发一个用户本来就能发的请求"。</p>
 */
@Slf4j
abstract class AbstractReadOnlyTool {

    /** 工具返回文本里的时间格式（模型与前端卡片共用同一口径）。 */
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    /** 「数据截至」行的前缀：{@link #dataAsOfLine} 与 {@link #parseDataAsOf} 共用，保证写入与解析同源。 */
    private static final String DATA_AS_OF_PREFIX = "数据截至 ";
    /** {@code yyyy-MM-dd HH:mm} 的定长字符数，解析时按此截取。 */
    private static final int TIME_FMT_LENGTH = 16;

    protected final RestClient gateway;
    protected final AgentCaller caller;
    protected final String sessionId;
    protected final AgentProperties properties;
    protected final ReadOnlyToolGuard guard;
    protected final AgentAuditService auditService;
    protected final ObjectMapper objectMapper;

    protected AbstractReadOnlyTool(RestClient gateway, AgentCaller caller, String sessionId,
                                   AgentProperties properties, ReadOnlyToolGuard guard,
                                   AgentAuditService auditService, ObjectMapper objectMapper) {
        this.gateway = gateway;
        this.caller = caller;
        this.sessionId = sessionId;
        this.properties = properties;
        this.guard = guard;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
    }

    /**
     * 工具名。与 {@code @Tool(name=…)} 必须一致——注解要求编译期常量，故常量留在各子类，
     * 这里只暴露取值入口，供审计与缓存键共用一处定义。
     */
    protected abstract String toolName();

    // ---------------- 闸门 ----------------

    /**
     * 进入两道闸门。返回<b>非 null 表示不必执行</b>：缓存命中（同会话同参数的既有结果）或配额用尽，
     * 调用方应直接把该文本返回给模型。
     *
     * @param paramsKey 调用参数的紧凑表示（参与哈希，保证"同参数才复用"）
     * @return 缓存文本 / 配额拒绝文案；两者都不拦时返回 null，表示"放行去查"
     */
    protected String gate(String paramsKey) {
        String cached = guard.cached(caller.userId(), sessionId, toolName(), paramsKey);
        if (cached != null) {
            auditService.recordToolRun(caller.userId(), sessionId, toolName(), paramsKey, "cache_hit", null);
            return cached;
        }
        if (!guard.tryConsumeQuota(caller.userId(), sessionId)) {
            // 语气刻意保持中性：这是在告诉模型"额度用完了"，不是平台故障。
            // 措辞必须点明"换个会话"：配额是**会话级累计**、且每次调用都续期，
            // 只要会话还活着计数就不会归零——说"稍后再问"是在给一条永远走不通的建议，
            // 用户会反复重试、反复失败。
            String msg = "本会话的数据查询额度已用完（额度按会话累计，在本会话里继续问也不会恢复）。"
                    + "请引导用户点击浮窗顶部的「清空会话」开一个新会话，或让他直接去对应页面自行查看。";
            auditService.recordToolRun(caller.userId(), sessionId, toolName(), paramsKey,
                    "quota_exceeded", "会话只读工具配额用尽");
            return msg;
        }
        return null;
    }

    /** 成功收尾：回写缓存 + 审计 ok。 */
    protected String succeed(String text, String paramsKey) {
        guard.cache(caller.userId(), sessionId, toolName(), paramsKey, text);
        auditService.recordToolRun(caller.userId(), sessionId, toolName(), paramsKey, "ok", null);
        return text;
    }

    /**
     * 失败收尾：给模型可读文案 + 记审计。
     *
     * <p>失败原因是最该可查的东西（它区分了"服务挂了"与"服务好好的、只是这个用户没店铺"），
     * 所以 {@code auditReason} 单独成参而不是从文案里截。</p>
     */
    protected String fail(String message, String paramsKey, String auditReason) {
        auditService.recordToolRun(caller.userId(), sessionId, toolName(), paramsKey, "failed", auditReason);
        return message;
    }

    // ---------------- 取数 ----------------

    /**
     * 经网关调用业务 API（透传用户 JWT，不做权限判断——见类注释）。
     *
     * <p><b>含文本参数时必须用模板占位符</b>（{@code ?keyword={kw}} + 把原文作为 {@code uriVars} 传入），
     * 不要把编码后的值拼进 URL：{@code RestClient} 默认按「模板与值都编码」处理，
     * 手拼的 {@code %} 会被再编码一次（{@code %E6%89%8B} → {@code %25E6%2589%258B}），
     * 服务端拿到的是乱码关键词。传原文、交给它编码才是对的。</p>
     *
     * @param urlTemplate 形如 {@code /api/search/shops?keyword={kw}&size={n}} 的模板
     * @param uriVars     模板变量（按出现顺序），只接受数值与文本原文
     * @return 响应根节点（含 {@code code}/{@code msg}/{@code data}），由调用方判 code
     * @throws Exception 网络/序列化异常，由调用方转成可读失败文案
     */
    protected JsonNode gatewayGet(String urlTemplate, Object... uriVars) throws Exception {
        String body = gateway.get()
                .uri(urlTemplate, uriVars)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + caller.token())
                .retrieve()
                .body(String.class);
        return objectMapper.readTree(body == null ? "{}" : body);
    }

    /** {@code data} 节点是否真的带了内容（JSON null 与字段缺失都算"没有"）。 */
    protected static boolean hasData(JsonNode data) {
        return data != null && !data.isNull() && !data.isMissingNode();
    }

    /**
     * 读一个数值字段；缺失、JSON null、非数值（如字符串）一律返回 {@code null}。
     *
     * <p><b>不要直接用 {@code JsonNode#decimalValue()}</b>：它无条件返回 {@code BigDecimal.ZERO}，
     * 而 {@code NullNode}/{@code MissingNode}/{@code TextNode} 都不覆写它（字节码已确认）。
     * 于是 {@code "price": null}、字段缺失、甚至 {@code "price": "9.9"}（字符串）
     * 全都会被静默读成 <b>0</b>——渲染出来是"￥0"，模型会如实告诉用户"价格 0 元"。
     * <b>数字看着像答案、其实是错的</b>，比查不到危险得多。</p>
     */
    protected static BigDecimal decimalOrNull(JsonNode node) {
        return node != null && node.isNumber() ? node.decimalValue() : null;
    }

    // ---------------- 渲染辅助 ----------------

    /** 长度收敛：超出配置上限即截断并注明（工具返回会进模型上下文，不收敛就是让模型决定花多少钱）。 */
    protected String truncate(String text) {
        int max = Math.max(200, properties.getReadOnly().getMaxResultChars());
        if (text.length() <= max) {
            return text;
        }
        // 日志里带上工具名与调用者：截断说明模型要的数据超出了单次可承载量，是调整上限的依据
        log.warn("只读工具 {} 结果超过 {} 字符已截断，userId={} sessionId={}",
                toolName(), max, caller.userId(), sessionId);
        return text.substring(0, max) + "\n（结果过长已截断）";
    }

    /** 单字段长度钳制：用于商品标题、被拒原因这类可能很长的自由文本。 */
    protected static String clamp(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    /** ISO 时间 → {@code yyyy-MM-dd HH:mm}；解析失败原样返回，空则 null。 */
    protected static String formatTime(String iso) {
        if (!StringUtils.hasText(iso)) {
            return null;
        }
        try {
            return LocalDateTime.parse(iso).format(TIME_FMT);
        } catch (Exception e) {
            return iso;
        }
    }

    /**
     * 数据时间戳行：模型与用户都要能看出"这些数据不是此刻的"。
     *
     * @param caveat 该数据源的时效性提醒（各工具措辞不同，如订单强调"支付/退款以页面为准"）
     */
    protected static String dataAsOfLine(String caveat) {
        String head = DATA_AS_OF_PREFIX + LocalDateTime.now().format(TIME_FMT);
        return StringUtils.hasText(caveat) ? head + "（" + caveat + "）" : head;
    }

    /**
     * 从工具结果文本里取回「数据截至」时间（供 SSE {@code tool_result} 事件展示）。
     *
     * <p><b>为什么从文本里反解，而不是让工具另存一个字段上报</b>：缓存命中时工具并不重新取数，
     * 沿途保存的时间戳极易退化成"当前时间"——那正是用户会把 30 秒前的旧数据当成实时数据的那条路。
     * 而这段文本本身就是要被缓存、被回灌给模型的那份数据，其内嵌时间必然与数据一致：
     * 写入用 {@link #dataAsOfLine}、解析用本方法，同一处定义的两个方向，口径不会漂移。</p>
     *
     * @return 解析出的时间；文本里没有该行（失败文案、无数据的回答）时返回 null
     */
    static LocalDateTime parseDataAsOf(String text) {
        if (!StringUtils.hasText(text)) {
            return null;
        }
        int idx = text.indexOf(DATA_AS_OF_PREFIX);
        if (idx < 0) {
            return null;
        }
        int from = idx + DATA_AS_OF_PREFIX.length();
        if (text.length() < from + TIME_FMT_LENGTH) {
            return null;
        }
        try {
            return LocalDateTime.parse(text.substring(from, from + TIME_FMT_LENGTH), TIME_FMT);
        } catch (Exception e) {
            return null;
        }
    }
}
