package com.lingmart.agent.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lingmart.agent.config.AgentProperties;
import com.lingmart.agent.dto.AgentCaller;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.util.Map;

/**
 * 「查我的订单」只读工具（{@link ToolRegistry.Category#READ_ONLY_SELF}）。
 *
 * <p><b>参数里没有主体</b>：方法签名只有筛选与条数，"查谁的订单"由服务端从 JWT 决定
 * （网关据 JWT 设置买家身份）。模型没有"查别人"这个旋钮，从根上杜绝被诱导越权。</p>
 *
 * <p><b>输出经过三重收敛</b>：字段白名单（只取订单号/金额/状态/时间）→ 条数上限（≤10）→
 * 总字符上限（超出截断并注明未展示条数）。工具返回会进模型上下文，不收敛就是让模型决定花多少钱。</p>
 *
 * <p>缓存、配额、取数、审计等公共链路见 {@link AbstractReadOnlyTool}。</p>
 */
@Slf4j
public class OrderQueryTools extends AbstractReadOnlyTool {

    /** 订单状态码 → 中文（与订单服务 order_status 语义一致） */
    private static final Map<Integer, String> ORDER_STATUS = Map.of(
            0, "待支付", 1, "已支付", 2, "已发货", 3, "已完成", 4, "已取消", 5, "已退款");

    /** 退款状态码 → 中文（仅在有退款时展示） */
    private static final Map<Integer, String> REFUND_STATUS = Map.of(
            1, "退款中", 2, "已退款", 3, "退款被拒");

    /** 工具名（与 {@code @Tool(name=…)} 一致，审计与缓存键共用一处定义） */
    private static final String TOOL_NAME = "query_my_orders";

    /** 单次最多返回条数：模型可以要得更少，但不能更多 */
    private static final int MAX_LIMIT = 10;
    private static final int DEFAULT_LIMIT = 5;

    public OrderQueryTools(RestClient gateway, AgentCaller caller, String sessionId,
                           AgentProperties properties, ReadOnlyToolGuard guard,
                           AgentAuditService auditService, ObjectMapper objectMapper) {
        super(gateway, caller, sessionId, properties, guard, auditService, objectMapper);
    }

    @Override
    protected String toolName() {
        return TOOL_NAME;
    }

    /**
     * 查询当前用户自己的订单。
     *
     * @param orderStatus 可选，按订单状态筛选：0待支付 1已支付 2已发货 3已完成 4已取消 5已退款；不传=全部
     * @param limit       可选，返回条数上限（1~10，默认 5）
     * @return 给模型看的紧凑文本（含总条数；失败时为可读的失败原因，不抛异常）
     */
    @Tool(name = "query_my_orders",
            description = "查询【当前用户本人】的订单列表，支持按订单状态筛选，返回订单号/金额/状态/时间。"
                    + "用户问「我有几个待支付订单」「我的订单到哪了」「最近买了什么」这类涉及自己订单的问题时调用。"
                    + "注意：只能查当前登录用户自己的订单，无法查询他人订单。")
    public String queryMyOrders(
            @ToolParam(description = "订单状态筛选：0待支付 1已支付 2已发货 3已完成 4已取消 5已退款。"
                    + "不传表示全部状态。用户问「待支付订单有几个」时应传 0。", required = false)
            Integer orderStatus,
            @ToolParam(description = "最多返回几条，取值 1~10，默认 5。只影响列出的明细条数，"
                    + "总条数始终准确返回。", required = false)
            Integer limit) {

        Integer status = normalizeStatus(orderStatus);
        if (orderStatus != null && status == null) {
            // 参数非法属于模型调用失误，不是执行失败：既不占配额也不记 failed，直接回一句让它重来
            return "订单状态参数非法（只接受 0~5）。请重新调用，或省略该参数查询全部订单。";
        }
        int size = normalizeLimit(limit);
        String paramsKey = "status=" + status + ",size=" + size;

        // 两道闸门：缓存命中或配额用尽时直接返回
        String gated = gate(paramsKey);
        if (gated != null) {
            return gated;
        }

        // 经网关调用业务 API（透传用户 JWT，不做权限判断）
        JsonNode data;
        try {
            JsonNode root = gatewayGet("/api/order/list?page=1&size=" + size
                    + (status == null ? "" : "&orderStatus=" + status));
            if (root.path("code").asInt() != 200) {
                return fail("订单服务返回失败：" + root.path("msg").asText("未知原因"),
                        paramsKey, "订单服务返回非成功码");
            }
            data = root.path("data");
        } catch (Exception e) {
            // 只在日志记原因（可能含 URL），给模型的文案不含内部细节
            log.warn("查询订单失败 sessionId={}：{}", sessionId, e.getMessage());
            return fail("查询订单失败，请稍后再试或引导用户去「我的订单」页面查看。",
                    paramsKey, "调用订单接口异常");
        }

        String text = render(data, status);
        if (text == null) {
            // 两种情况：数据格式异常，或越权交叉校验未通过（后者已在 render 里打 ERROR）
            return fail("订单数据格式异常，请引导用户去「我的订单」页面查看。",
                    paramsKey, "数据格式异常/越权校验未通过");
        }
        return succeed(text, paramsKey);
    }

    // ---------------- 渲染 ----------------

    /**
     * 把接口返回渲染成给模型看的紧凑文本。
     *
     * @return 渲染结果；数据格式异常返回 null
     */
    private String render(JsonNode data, Integer status) {
        JsonNode list = data.path("list");
        if (!list.isArray()) {
            return null;
        }
        long total = data.path("total").asLong(0);

        // 交叉校验：接口必须只返回"我自己的"订单。网关按 JWT 决定买家，这里再核一次，
        // 一旦不一致说明链路出了问题（越权或接口语义变更）——此时宁可什么都不给，也不能把
        // 别人的订单喂给模型和用户。fail-closed。
        for (JsonNode order : list) {
            JsonNode buyerIdNode = order.path("buyerId");
            if (!buyerIdNode.isNull() && buyerIdNode.asLong() != caller.userId()) {
                log.error("【越权风险】订单查询返回了非当前用户的订单：期望 buyerId={}，实际={}，sessionId={}",
                        caller.userId(), buyerIdNode.asLong(), sessionId);
                return null;
            }
        }

        StringBuilder sb = new StringBuilder(512);
        sb.append("【我的订单】")
                .append(status == null ? "全部状态" : "筛选状态：" + ORDER_STATUS.getOrDefault(status, String.valueOf(status)))
                .append("；共 ").append(total).append(" 条")
                .append("（本次列出 ").append(list.size()).append(" 条）");

        int shown = 0;
        for (JsonNode order : list) {
            // 字段白名单：只取这四项。订单备注、退款原因、明细、地址等一律不取——
            // 它们要么是自由文本（可能含隐私），要么对本类问题无用。
            String orderSn = order.path("orderSn").asText("");
            BigDecimal amount = decimalOrNull(order.path("totalAmount"));
            int orderStatus = order.path("orderStatus").asInt(-1);
            String createTime = formatTime(order.path("createTime").asText(null));
            Integer refundStatus = order.path("refundStatus").isNull() ? null : order.path("refundStatus").asInt();

            sb.append("\n- 订单号 ").append(orderSn)
                    .append(" | 金额 ").append(amount == null ? "未知" : "￥" + amount.toPlainString())
                    .append(" | 状态 ").append(ORDER_STATUS.getOrDefault(orderStatus, "状态" + orderStatus))
                    .append(" | 下单 ").append(createTime == null ? "未知" : createTime);
            if (refundStatus != null && refundStatus != 0) {
                sb.append(" | ").append(REFUND_STATUS.getOrDefault(refundStatus, "退款状态" + refundStatus));
            }
            shown++;
        }

        long notShown = total - shown;
        if (notShown > 0) {
            sb.append("\n（另有 ").append(notShown)
                    .append(" 条未展示。如需查看，可指定订单状态筛选，或让用户去「我的订单」页面翻页查看）");
        }
        sb.append('\n').append(dataAsOfLine("订单状态可能已变化，涉及支付/退款的关键结论请以页面为准"));

        return truncate(sb.toString());
    }

    /** 状态码归一化；越界返回 null（由调用方区分"未传"与"非法"）。 */
    private static Integer normalizeStatus(Integer status) {
        if (status == null) {
            return null;
        }
        return ORDER_STATUS.containsKey(status) ? status : null;
    }

    /** 条数钳制：模型要多少不重要，服务端说了算（只钳响应不钳请求等于没钳）。 */
    private static int normalizeLimit(Integer limit) {
        if (limit == null) {
            return DEFAULT_LIMIT;
        }
        return Math.min(Math.max(1, limit), MAX_LIMIT);
    }
}
