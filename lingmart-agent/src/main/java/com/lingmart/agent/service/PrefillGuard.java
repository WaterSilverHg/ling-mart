package com.lingmart.agent.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lingmart.agent.dto.PrefillShopRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * 「预填开店申请」草稿的准入闸门（v0.28）。
 *
 * <p>服务端在这里只做一件事：**拒绝"没东西可填"的空草稿**，并把原因回灌给模型让它自己修正后重来。
 * 这与只读工具的 {@code AbstractReadOnlyTool.gate} 同一思路——不合格就不放行，但要让模型知道为什么。</p>
 *
 * <p><b>刻意不做数据清洗</b>（不裁字段长度、不剔除"信息不全"的条目），三个理由：</p>
 * <ol>
 *   <li><b>剔除对用户更不利</b>：把缺价格的条目也照填进去，并在卡片与表单上提示「N 条信息不全，需你补全」，
 *       用户能看见并补齐；静默丢弃则用户察觉不到少了一条。</li>
 *   <li><b>长度与数值的最终裁决权不在服务端</b>：后端有校验注解（{@code @Size} / {@code @DecimalMin}），
 *       前端有 {@code isValidProduct}，用户提交前还会核对。服务端替它们裁剪会**掩盖模型的解析错误**。</li>
 *   <li><b>条数上限放在前端</b>（渲染与填入时截断并标注）：服务端要截断就得改写模型的工具调用参数，
 *       那意味着把 arguments 反序列化→裁剪→重新序列化，引入一整条易错的链路，收益却只是省几行前端代码。</li>
 * </ol>
 *
 * <p><b>真正的安全边界不在这里</b>，而在工具参数的结构固定——{@code PrefillShopRequest} 的字段集合是封闭的，
 * 模型只能往这些字段放值，没有「填哪个字段 / 选哪个元素」的自由度。本闸门只负责可用性。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PrefillGuard {

    /** 走本闸门的工具名（其它写工具如 navigate/click 不经过它） */
    private static final String TOOL_PREFILL = "prefill_shop_application";

    private final ObjectMapper objectMapper;

    /**
     * 分流结果。
     *
     * @param accepted 可以下发成确认卡片的写操作
     * @param rejected 未通过的工具回执（回灌给模型，让它看到原因后修正）
     */
    public record Result(List<AssistantMessage.ToolCall> accepted,
                         List<ToolResponseMessage.ToolResponse> rejected) {
    }

    /**
     * 逐个筛查写操作。非本闸门负责的工具一律原样放行。
     */
    public Result screen(List<AssistantMessage.ToolCall> writeCalls) {
        List<AssistantMessage.ToolCall> accepted = new ArrayList<>();
        List<ToolResponseMessage.ToolResponse> rejected = new ArrayList<>();
        for (AssistantMessage.ToolCall call : writeCalls) {
            String reason = rejectionReason(call);
            if (reason == null) {
                accepted.add(call);
            } else {
                log.warn("预填草稿未通过闸门：toolCallId={}, reason={}", call.id(), reason);
                rejected.add(new ToolResponseMessage.ToolResponse(call.id(), call.name(), reason));
            }
        }
        return new Result(accepted, rejected);
    }

    /**
     * @return null = 放行；否则返回给模型的拒绝原因
     */
    private String rejectionReason(AssistantMessage.ToolCall call) {
        if (!TOOL_PREFILL.equals(call.name())) {
            return null;
        }
        PrefillShopRequest draft;
        try {
            JsonNode node = objectMapper.readTree(call.arguments()).path("draft");
            if (node.isMissingNode() || node.isNull()) {
                return "预填草稿为空：必须在 draft 参数里给出店铺信息与商品清单。";
            }
            draft = objectMapper.treeToValue(node, PrefillShopRequest.class);
        } catch (Exception e) {
            // 参数结构不对（缺 draft、字段类型不符等）：告诉模型具体问题，让它重发
            return "预填草稿无法解析（" + e.getMessage() + "），请按工具参数的字段结构重新提交。";
        }
        if (draft == null || CollectionUtils.isEmpty(draft.getProducts())) {
            return "预填草稿里没有任何商品条目。请先确认用户提供的材料里是否包含商品信息："
                    + "若包含，请解析后重新提交；若不包含（比如只给了店铺名），就不要调用本工具，"
                    + "直接告诉用户还需要提供哪些商品信息。";
        }
        return null;
    }
}
