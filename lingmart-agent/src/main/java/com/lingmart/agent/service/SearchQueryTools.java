package com.lingmart.agent.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lingmart.agent.config.AgentProperties;
import com.lingmart.agent.dto.AgentCaller;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 「搜索店铺」只读工具（{@link ToolRegistry.Category#READ_ONLY_PUBLIC}）。
 *
 * <p><b>为什么是第一个 PUBLIC 类工具</b>：前三个工具都是 {@code READ_ONLY_SELF}——"只能查我自己的"，
 * 靠"参数里没有主体"从根上杜绝越权。而搜索查的是**公开在售商品**，本来谁都能看，
 * 所以类别是 PUBLIC。安全含义不同：它不涉及"查谁"的问题，但**结果里的内容依然可能是用户可写的**
 * （店铺名、商品标题），所以渲染时只取白名单标量、且整段结果仍会走 {@code <tool_result>} 边界标签。</p>
 *
 * <p><b>为什么用店铺聚合接口而不是商品检索接口</b>：平台的发现模型就是"按店铺聚合"
 * （首页搜索框走的也是 {@code /api/search/shops}，商品不直接作为搜索结果展示）。
 * 而且这个接口的字段全是干净的标量（店铺名/命中数/起售价），不像商品检索会带
 * 带 {@code <em>} 标签的高亮片段——那种 HTML 片段进模型上下文既无用又容易干扰。</p>
 *
 * <p><b>刻意不暴露 {@code categoryId}</b>：平台分类已下线（v0.9），传了也没有意义。</p>
 *
 * <p>缓存、配额、取数、审计等公共链路见 {@link AbstractReadOnlyTool}。</p>
 */
@Slf4j
public class SearchQueryTools extends AbstractReadOnlyTool {

    private static final String TOOL_NAME = "search_shops";

    /** 单次最多返回条数：模型可以要得更少，但不能更多 */
    private static final int MAX_LIMIT = 10;
    private static final int DEFAULT_LIMIT = 5;
    /** 店铺名长度上限（用户可写，且要进模型上下文） */
    private static final int MAX_NAME = 40;
    /** 关键词长度上限：检索接口本身没限制，但超长关键词只会浪费一次嵌入与 ES 查询 */
    private static final int MAX_KEYWORD = 50;

    public SearchQueryTools(RestClient gateway, AgentCaller caller, String sessionId,
                            AgentProperties properties, ReadOnlyToolGuard guard,
                            AgentAuditService auditService, ObjectMapper objectMapper) {
        super(gateway, caller, sessionId, properties, guard, auditService, objectMapper);
    }

    @Override
    protected String toolName() {
        return TOOL_NAME;
    }

    /**
     * 按关键词（可叠加价格区间）搜索平台上的在售店铺。
     *
     * @param keyword  搜索关键词（按商品标题匹配），可为空=按价格条件浏览全部
     * @param minPrice 可选，最低价（元）
     * @param maxPrice 可选，最高价（元）
     * @param limit    可选，返回条数上限（1~10，默认 5）
     * @return 给模型看的紧凑文本（含命中店铺总数；失败时为可读的失败原因，不抛异常）
     */
    @Tool(name = "search_shops",
            description = "在平台上搜索【店铺】（按在售商品标题匹配、按店铺聚合），返回店铺名、命中商品数、"
                    + "最低起售价与店铺 ID。用户问「有没有卖 X 的」「哪里能买到 X」「X 大概多少钱」"
                    + "这类想找商品/店铺的问题时调用。拿到结果后可再用 navigate_to 带用户去 /shop/{店铺ID} 选购。"
                    + "注意：只能搜公开在售的商品，查不到任何用户的私有数据（订单/账号等）。")
    public String searchShops(
            @ToolParam(description = "搜索关键词，如「手机壳」「iPhone 13」。不传则按价格条件浏览全部在售店铺。",
                    required = false)
            String keyword,
            @ToolParam(description = "最低价（元），可为空", required = false) BigDecimal minPrice,
            @ToolParam(description = "最高价（元），可为空。用户问「100 元以内」时传 100。", required = false)
            BigDecimal maxPrice,
            @ToolParam(description = "最多返回几条，取值 1~10，默认 5。只影响列出的明细条数，"
                    + "店铺总数始终准确返回。", required = false)
            Integer limit) {

        // 参数非法属于模型调用失误，不是执行失败：既不占配额也不记 failed，直接回一句让它重来
        String kw = StringUtils.hasText(keyword) ? keyword.trim() : null;
        if (kw != null && kw.length() > MAX_KEYWORD) {
            return "关键词过长（最多 " + MAX_KEYWORD + " 字），请精简后重新调用。";
        }
        if (minPrice != null && minPrice.signum() < 0) {
            return "最低价不能为负数。请重新调用，或省略该参数。";
        }
        if (maxPrice != null && maxPrice.signum() < 0) {
            return "最高价不能为负数。请重新调用，或省略该参数。";
        }
        if (minPrice != null && maxPrice != null && minPrice.compareTo(maxPrice) > 0) {
            return "最低价大于最高价。请重新调用，或交换两个参数。";
        }
        int size = normalizeLimit(limit);
        String paramsKey = "kw=" + (kw == null ? "" : kw) + ",min=" + minPrice + ",max=" + maxPrice + ",size=" + size;

        String gated = gate(paramsKey);
        if (gated != null) {
            return gated;
        }

        JsonNode data;
        try {
            // 关键词走模板变量：RestClient 会对值做一次编码。手拼编码后的串会被再编码一次（见 gatewayGet 说明）
            StringBuilder url = new StringBuilder("/api/search/shops?page=1&size=").append(size);
            List<Object> vars = new ArrayList<>();
            if (kw != null) {
                url.append("&keyword={kw}");
                vars.add(kw);
            }
            if (minPrice != null) {
                url.append("&minPrice={min}");
                vars.add(minPrice);
            }
            if (maxPrice != null) {
                url.append("&maxPrice={max}");
                vars.add(maxPrice);
            }
            JsonNode root = gatewayGet(url.toString(), vars.toArray());
            if (root.path("code").asInt() != 200) {
                return fail("检索服务返回失败：" + root.path("msg").asText("未知原因"),
                        paramsKey, "检索服务返回非成功码");
            }
            data = root.path("data");
        } catch (Exception e) {
            log.warn("搜索店铺失败 sessionId={}：{}", sessionId, e.getMessage());
            return fail("搜索失败，请稍后再试或引导用户到首页搜索框自行查找。",
                    paramsKey, "调用检索接口异常");
        }

        String text = render(data, kw, minPrice, maxPrice);
        if (text == null) {
            return fail("检索数据格式异常，请引导用户到首页搜索框自行查找。",
                    paramsKey, "数据格式异常");
        }
        return succeed(text, paramsKey);
    }

    // ---------------- 渲染 ----------------

    /**
     * 把接口返回渲染成给模型看的紧凑文本。
     *
     * @return 渲染结果；数据格式异常返回 null
     */
    private String render(JsonNode data, String kw, BigDecimal minPrice, BigDecimal maxPrice) {
        JsonNode list = data.path("list");
        if (!list.isArray()) {
            return null;
        }
        long total = data.path("total").asLong(0);

        StringBuilder sb = new StringBuilder(512);
        sb.append("【店铺搜索】").append(filterLabel(kw, minPrice, maxPrice))
                .append("；共 ").append(total).append(" 家店铺")
                .append("（本次列出 ").append(list.size()).append(" 家）");

        if (total == 0) {
            // 明确区分"确实没有"与"搜错了"：给出可操作的下一步，避免模型反复换关键词重试
            sb.append("\n没有符合条件的店铺。可以换个更宽泛的关键词，或去掉价格条件；"
                    + "也可以直接引导用户到首页浏览全部入驻店铺。");
            sb.append('\n').append(dataAsOfLine("在售商品与价格可能已变化"));
            return truncate(sb.toString());
        }

        int shown = 0;
        for (JsonNode shop : list) {
            // 字段白名单：只取这几个。image 等对"有没有卖 X"无用，不进上下文。
            long shopId = shop.path("shopId").asLong(0);
            String shopName = clamp(shop.path("shopName").asText(""), MAX_NAME);
            int productCount = shop.path("productCount").asInt(0);
            BigDecimal priceFrom = decimalOrNull(shop.path("priceFrom"));

            sb.append("\n- ").append(StringUtils.hasText(shopName) ? shopName : "(未命名店铺)")
                    .append(" | 命中 ").append(productCount).append(" 件在售")
                    .append(" | 起售 ").append(priceFrom == null ? "未知" : "￥" + priceFrom.toPlainString())
                    .append(" | 店铺ID ").append(shopId);
            shown++;
        }

        long notShown = total - shown;
        if (notShown > 0) {
            sb.append("\n（另有 ").append(notShown)
                    .append(" 家未展示。可加价格条件缩小范围，或让用户到首页搜索框自己翻页看）");
        }
        // 明确告诉模型"这个 ID 能干嘛"：否则它多半只会把店铺名念一遍，用户还得自己去找入口
        sb.append("\n（带用户去某家店：navigate_to 到 /shop/{店铺ID}）");
        sb.append('\n').append(dataAsOfLine("在售商品与价格可能已变化，以店铺主页为准"));

        return truncate(sb.toString());
    }

    /** 筛选条件的中文描述（用于回答"你在搜什么"，避免模型把筛选后的数字说成全量）。 */
    private static String filterLabel(String kw, BigDecimal minPrice, BigDecimal maxPrice) {
        StringBuilder sb = new StringBuilder();
        sb.append(StringUtils.hasText(kw) ? "关键词「" + kw + "」" : "全部在售店铺");
        if (minPrice != null || maxPrice != null) {
            sb.append("，价格 ");
            sb.append(minPrice == null ? "不限" : minPrice.toPlainString() + " 元起");
            if (maxPrice != null) {
                sb.append(" ~ ").append(maxPrice.toPlainString()).append(" 元以内");
            }
        }
        return sb.toString();
    }

    /** 条数钳制：模型要多少不重要，服务端说了算。 */
    private static int normalizeLimit(Integer limit) {
        if (limit == null) {
            return DEFAULT_LIMIT;
        }
        return Math.min(Math.max(1, limit), MAX_LIMIT);
    }
}
