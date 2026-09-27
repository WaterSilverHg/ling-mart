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
import java.util.Map;

/**
 * 「查我的商品」只读工具（{@link ToolRegistry.Category#READ_ONLY_SELF}）。
 *
 * <p><b>参数里没有主体</b>：只有上下架/审核状态与条数三个筛选，店铺由服务端从身份经
 * {@code shopFeignClient} 解析（见 {@code ProductServiceImpl#myProducts}）。
 * 模型没有"查别人店铺的商品"这个旋钮。</p>
 *
 * <p><b>为什么筛选项由服务端下推给业务接口、而不是取回来自己过滤</b>：分页接口返回的
 * {@code total} 是"符合筛选条件的总数"。若把筛选放在本地，{@code total} 就退化成"全部商品数"，
 * 于是「我有几个商品没通过审核」会被答成全部商品数——<b>数字看着像答案，其实是错的</b>，
 * 比答不出来更危险。故 {@code /api/product/my} 增加了可选筛选参数。</p>
 *
 * <p><b>商品标题是用户可写内容</b>：它会随工具结果进入模型上下文，因此本类不做任何拼接解释，
 * 由 {@code AgentTurnService} 统一包进 {@code <tool_result>} 边界标签并中和同名标签。</p>
 *
 * <p>缓存、配额、取数、审计等公共链路见 {@link AbstractReadOnlyTool}。</p>
 */
@Slf4j
public class ProductQueryTools extends AbstractReadOnlyTool {

    /** 上下架状态（spu.status） */
    private static final Map<Integer, String> SHELF_STATUS = Map.of(0, "已下架", 1, "已上架");

    /** 商品审核状态（spu.review_status） */
    private static final Map<Integer, String> REVIEW_STATUS = Map.of(
            0, "待审核", 1, "已通过", 2, "已拒绝", 3, "待重审");

    private static final String TOOL_NAME = "query_my_products";

    /** 单次最多返回条数：模型可以要得更少，但不能更多 */
    private static final int MAX_LIMIT = 10;
    private static final int DEFAULT_LIMIT = 5;
    /** 商品标题长度上限（用户可写，且要进模型上下文） */
    private static final int MAX_TITLE = 40;

    public ProductQueryTools(RestClient gateway, AgentCaller caller, String sessionId,
                             AgentProperties properties, ReadOnlyToolGuard guard,
                             AgentAuditService auditService, ObjectMapper objectMapper) {
        super(gateway, caller, sessionId, properties, guard, auditService, objectMapper);
    }

    @Override
    protected String toolName() {
        return TOOL_NAME;
    }

    /**
     * 查询当前用户自己店铺的商品。
     *
     * @param status       可选，按上下架状态筛选：0 已下架，1 已上架；不传=全部
     * @param reviewStatus 可选，按审核状态筛选：0 待审核，1 已通过，2 已拒绝，3 待重审；不传=全部
     * @param limit        可选，返回条数上限（1~10，默认 5）
     * @return 给模型看的紧凑文本（含总条数；失败时为可读的失败原因，不抛异常）
     */
    @Tool(name = "query_my_products",
            description = "查询【当前用户本人店铺】的商品列表，支持按上下架状态、审核状态筛选，"
                    + "返回商品标题/价格/销量/库存与状态。用户问「我有几个商品」「我的商品都上架了吗」"
                    + "「哪些商品还没通过审核」这类涉及自己商品的问题时调用。"
                    + "注意：只能查当前登录用户自己店铺的商品，无法查询他人店铺的商品。")
    public String queryMyProducts(
            @ToolParam(description = "上下架状态筛选：0 已下架，1 已上架。不传表示全部。"
                    + "用户问「有几个商品在卖」时应传 1。", required = false)
            Integer status,
            @ToolParam(description = "审核状态筛选：0 待审核，1 已通过，2 已拒绝，3 待重审。不传表示全部。"
                    + "用户问「哪些商品没通过审核」时应传 2。", required = false)
            Integer reviewStatus,
            @ToolParam(description = "最多返回几条，取值 1~10，默认 5。只影响列出的明细条数，"
                    + "总条数始终准确返回。", required = false)
            Integer limit) {

        // 参数非法属于模型调用失误，不是执行失败：既不占配额也不记 failed，直接回一句让它重来
        if (status != null && !SHELF_STATUS.containsKey(status)) {
            return "上下架状态参数非法（只接受 0 已下架 / 1 已上架）。请重新调用，或省略该参数。";
        }
        if (reviewStatus != null && !REVIEW_STATUS.containsKey(reviewStatus)) {
            return "审核状态参数非法（只接受 0~3）。请重新调用，或省略该参数。";
        }
        int size = normalizeLimit(limit);
        String paramsKey = "status=" + status + ",review=" + reviewStatus + ",size=" + size;

        String gated = gate(paramsKey);
        if (gated != null) {
            return gated;
        }

        JsonNode data;
        try {
            JsonNode root = gatewayGet("/api/product/my?page=1&size=" + size
                    + (status == null ? "" : "&status=" + status)
                    + (reviewStatus == null ? "" : "&reviewStatus=" + reviewStatus));
            if (root.path("code").asInt() != 200) {
                return fail("商品服务返回失败：" + root.path("msg").asText("未知原因"),
                        paramsKey, "商品服务返回非成功码");
            }
            data = root.path("data");
        } catch (Exception e) {
            log.warn("查询我的商品失败 sessionId={}：{}", sessionId, e.getMessage());
            return fail("查询商品失败，请稍后再试或引导用户去「我的商品」页面查看。",
                    paramsKey, "调用商品接口异常");
        }

        String text = render(data, status, reviewStatus);
        if (text == null) {
            return fail("商品数据格式异常，请引导用户去「我的商品」页面查看。",
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
    private String render(JsonNode data, Integer status, Integer reviewStatus) {
        JsonNode list = data.path("list");
        if (!list.isArray()) {
            return null;
        }
        long total = data.path("total").asLong(0);

        StringBuilder sb = new StringBuilder(512);
        sb.append("【我的商品】").append(filterLabel(status, reviewStatus))
                .append("；共 ").append(total).append(" 条")
                .append("（本次列出 ").append(list.size()).append(" 条）");

        if (total == 0) {
            // 接口对"没有店铺"与"店铺里没有商品"都返回 0 条，无从区分，
            // 所以把两种可能都告诉模型并给出确认途径——否则它会把"你还没开店"答成"你有 0 个商品"
            sb.append("\n没有符合条件的商品。如果用户其实还没开店，这个结果也可能代表「没有店铺」"
                    + "而不是「没有商品」；可调用 query_my_shop 确认后再回答。");
            sb.append('\n').append(dataAsOfLine("商品状态以「我的商品」页面为准"));
            return truncate(sb.toString());
        }

        int shown = 0;
        for (JsonNode spu : list) {
            // 字段白名单：只取这几项。商品描述、详情图、SKU 明细等一律不取——
            // 它们要么是长自由文本（注入面），要么对本类问题无用。
            long spuId = spu.path("spuId").asLong(0);
            String title = clamp(spu.path("title").asText(""), MAX_TITLE);
            BigDecimal price = decimalOrNull(spu.path("price"));
            Integer saleCount = spu.path("saleCount").isNull() ? null : spu.path("saleCount").asInt();
            int shelf = spu.path("status").asInt(-1);
            int review = spu.path("reviewStatus").asInt(-1);

            sb.append("\n- 商品ID ").append(spuId)
                    .append(" | ").append(StringUtils.hasText(title) ? title : "(无标题)")
                    .append(" | ￥").append(price == null ? "未知" : price.toPlainString())
                    .append(" | 销量 ").append(saleCount == null ? "未知" : saleCount)
                    .append(" | ").append(SHELF_STATUS.getOrDefault(shelf, "状态" + shelf))
                    .append(" | ").append(REVIEW_STATUS.getOrDefault(review, "状态" + review))
                    .append(stockSummary(spu.path("stocks")));
            shown++;
        }

        long notShown = total - shown;
        if (notShown > 0) {
            sb.append("\n（另有 ").append(notShown)
                    .append(" 条未展示。如需查看，可指定上下架或审核状态筛选，或让用户去「我的商品」页面翻页查看）");
        }
        sb.append('\n').append(dataAsOfLine("库存与审核状态可能已变化，以「我的商品」页面为准"));

        return truncate(sb.toString());
    }

    /** 筛选条件的中文描述（同时用于回答"你在查什么"，避免模型把筛选后的数字说成全量）。 */
    private static String filterLabel(Integer status, Integer reviewStatus) {
        if (status == null && reviewStatus == null) {
            return "全部状态";
        }
        StringBuilder sb = new StringBuilder("筛选：");
        if (status != null) {
            sb.append(SHELF_STATUS.getOrDefault(status, String.valueOf(status)));
        }
        if (reviewStatus != null) {
            if (status != null) {
                sb.append(" + ");
            }
            sb.append(REVIEW_STATUS.getOrDefault(reviewStatus, String.valueOf(reviewStatus)));
        }
        return sb.toString();
    }

    /**
     * 库存摘要（取自 {@code stocks}，仅「我的商品」接口会填充）。
     *
     * <p>不限量与限量分开算：不限量商品的 {@code available} 为 null，若直接累加会把"不限量"
     * 误报成"剩余 0 件"。</p>
     *
     * @return 形如 {@code " | 剩余 12 件（2 个规格售罄）"} 的片段；无库存信息时返回空串
     */
    private static String stockSummary(JsonNode stocks) {
        if (stocks == null || !stocks.isArray() || stocks.size() == 0) {
            return "";
        }
        boolean anyUnlimited = false;
        int available = 0;
        int soldOut = 0;
        for (JsonNode sku : stocks) {
            if (sku.path("unlimited").asBoolean(false)) {
                anyUnlimited = true;
                continue;
            }
            JsonNode avail = sku.path("available");
            if (avail.isNumber()) {
                int v = avail.asInt();
                available += v;
                if (v <= 0) {
                    soldOut++;
                }
            }
        }
        if (anyUnlimited) {
            return " | 不限量";
        }
        StringBuilder sb = new StringBuilder(" | 剩余 ").append(available).append(" 件");
        if (soldOut > 0) {
            sb.append("（").append(soldOut).append(" 个规格售罄）");
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
