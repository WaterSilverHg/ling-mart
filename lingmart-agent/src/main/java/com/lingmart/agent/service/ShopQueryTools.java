package com.lingmart.agent.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lingmart.agent.config.AgentProperties;
import com.lingmart.agent.dto.AgentCaller;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import java.util.Map;

/**
 * 「查我的开店状态」只读工具（{@link ToolRegistry.Category#READ_ONLY_SELF}）。
 *
 * <p><b>为什么一个工具打两个接口</b>：用户问的是「我的店铺审核过了吗」，而这在数据上分两处——
 * 入驻申请（{@code shop_application}）与店铺（{@code shop}）。让模型自己调两个工具再拼结论，
 * 既费它一次调用预算，又容易出现"申请说通过、店铺说未启用"这种自相矛盾的回答。
 * 这里合并成一次调用、给出一句完整结论。</p>
 *
 * <p><b>零参数</b>：没有任何入参，模型连"查谁"的表达能力都没有，注入面为零。</p>
 *
 * <p><b>刻意不取 {@code description} 与 {@code draftContent}</b>：前者是店铺简介、
 * 后者是待重审的店铺信息草稿（JSON 串），二者都是<b>用户自己写的自由文本</b>——
 * 送进模型上下文就是一条现成的间接注入通道，而对"审核过了吗"这个问题毫无用处。</p>
 *
 * <p>缓存、配额、取数、审计等公共链路见 {@link AbstractReadOnlyTool}。</p>
 */
@Slf4j
public class ShopQueryTools extends AbstractReadOnlyTool {

    /** 入驻申请状态（shop_application.status） */
    private static final Map<Integer, String> APPLICATION_STATUS = Map.of(
            0, "待审核", 1, "已通过", 2, "已拒绝");

    /** 店铺营业状态（shop.status） */
    private static final Map<Integer, String> SHOP_STATUS = Map.of(
            0, "未启用", 1, "营业中", 2, "已禁用");

    /** 店铺审核状态（shop.review_status） */
    private static final Map<Integer, String> REVIEW_STATUS = Map.of(
            0, "待审核", 1, "已通过", 2, "已拒绝", 3, "待重审");

    private static final String TOOL_NAME = "query_my_shop";
    /** 无参数，参数键为常量（缓存键仍含 userId，见 ReadOnlyToolGuard） */
    private static final String PARAMS_KEY = "self";
    /** 店铺名 / 被拒原因的长度上限（都要进模型上下文，也都会显示在结果卡片上） */
    private static final int MAX_NAME = 40;
    private static final int MAX_REASON = 120;

    /** 入驻申请接口（主：回答"审核过了吗"） */
    private static final String URL_APPLICATION = "/api/shop/application/my";
    /** 店铺信息接口（次：补充营业状态与审核状态） */
    private static final String URL_MY_SHOP = "/api/shop/my";

    public ShopQueryTools(RestClient gateway, AgentCaller caller, String sessionId,
                          AgentProperties properties, ReadOnlyToolGuard guard,
                          AgentAuditService auditService, ObjectMapper objectMapper) {
        super(gateway, caller, sessionId, properties, guard, auditService, objectMapper);
    }

    @Override
    protected String toolName() {
        return TOOL_NAME;
    }

    /**
     * 查询当前用户自己的开店状态（入驻申请 + 店铺）。
     *
     * @return 给模型看的紧凑文本（失败时为可读的失败原因，不抛异常）
     */
    @Tool(name = "query_my_shop",
            description = "查询【当前用户本人】的开店状态：是否提交过入驻申请、申请是否通过、被拒原因、"
                    + "店铺是否在营业。用户问「我的店铺审核过了吗」「我开店申请通过了吗」「我的店怎么还没上线」"
                    + "这类涉及自己店铺的问题时调用。注意：只能查当前登录用户自己的店铺，无法查询他人店铺。")
    public String queryMyShop() {

        String gated = gate(PARAMS_KEY);
        if (gated != null) {
            return gated;
        }

        // ① 入驻申请：这是"审核过了吗"的主答案，取不到就是真失败
        JsonNode application;
        try {
            JsonNode root = gatewayGet(URL_APPLICATION);
            if (root.path("code").asInt() != 200) {
                return fail("查询入驻申请失败：" + root.path("msg").asText("未知原因"),
                        PARAMS_KEY, "入驻申请接口返回非成功码");
            }
            // data 为 null 是正常语义：该用户还没提交过申请（见 ShopServiceImpl#getMyApplication）
            application = root.path("data");
        } catch (Exception e) {
            log.warn("查询入驻申请失败 sessionId={}：{}", sessionId, e.getMessage());
            return fail("查询开店状态失败，请稍后再试或引导用户去「申请开店」页面查看。",
                    PARAMS_KEY, "调用入驻申请接口异常");
        }

        // ② 店铺信息：只是补充。没店铺时该接口以业务异常拒绝（"暂无店铺，请先提交入驻申请"），
        //    那是正常情况而非故障，所以这里吞掉失败、不让它影响主结论。
        JsonNode shop = null;
        try {
            JsonNode root = gatewayGet(URL_MY_SHOP);
            if (root.path("code").asInt() == 200) {
                shop = root.path("data");
            } else {
                log.debug("查询店铺信息未成功（多为「暂无店铺」的正常语义）sessionId={} msg={}",
                        sessionId, root.path("msg").asText(""));
            }
        } catch (Exception e) {
            log.debug("查询店铺信息异常，降级为仅返回申请状态 sessionId={}：{}", sessionId, e.getMessage());
        }

        return succeed(render(application, shop), PARAMS_KEY);
    }

    // ---------------- 渲染 ----------------

    /**
     * 把两次查询的结果合成一段给模型看的文本。
     *
     * <p>优先给一句明确结论（"已通过 / 审核中 / 被拒"），再补细节——用户问的就是这个结论，
     * 让模型从明细里自己推断既慢又容易说错。</p>
     */
    private String render(JsonNode application, JsonNode shop) {
        boolean hasApp = hasData(application);
        boolean hasShop = hasData(shop);

        StringBuilder sb = new StringBuilder(384);
        sb.append("【我的开店状态】");

        // 既没申请也没店铺：给出这句就够，别让模型去猜
        if (!hasApp && !hasShop) {
            sb.append("你还没有提交过入驻申请，目前也没有店铺。")
                    .append("\n如果想开店，可以到「申请开店」页面（路由 /shop/apply）填写并提交申请。");
            sb.append('\n').append(dataAsOfLine("以申请页面显示的状态为准"));
            return truncate(sb.toString());
        }

        if (hasApp) {
            int status = application.path("status").asInt(-1);
            String name = application.path("shopName").asText("");
            String time = formatTime(application.path("createTime").asText(null));

            sb.append("\n入驻申请：").append(APPLICATION_STATUS.getOrDefault(status, "状态" + status));
            if (StringUtils.hasText(name)) {
                sb.append("（申请店铺名：").append(clamp(name, MAX_NAME)).append("）");
            }
            if (time != null) {
                sb.append("，提交于 ").append(time);
            }
            if (status == 2) {
                String reason = application.path("rejectReason").asText("");
                sb.append("\n被拒原因：").append(StringUtils.hasText(reason)
                        ? clamp(reason, MAX_REASON) : "平台未填写原因");
                sb.append("\n可以修改资料后重新提交申请。");
            } else if (status == 0) {
                sb.append("\n当前正在审核中，尚未有结果。");
            } else if (status == 1) {
                sb.append("\n申请已通过。");
            }
        } else if (hasShop) {
            // 数据上少见（有店铺却查不到申请记录），如实说，别编一个申请状态出来
            sb.append("\n未查询到入驻申请记录，但已存在店铺（可能是平台早期数据）。");
        }

        if (hasShop) {
            int status = shop.path("status").asInt(-1);
            int reviewStatus = shop.path("reviewStatus").asInt(-1);
            String name = shop.path("shopName").asText("");
            sb.append("\n店铺：");
            if (StringUtils.hasText(name)) {
                sb.append(clamp(name, MAX_NAME)).append(" | ");
            }
            sb.append("营业状态 ").append(SHOP_STATUS.getOrDefault(status, "状态" + status))
                    .append(" | 审核状态 ").append(REVIEW_STATUS.getOrDefault(reviewStatus, "状态" + reviewStatus));
            if (reviewStatus == 3) {
                sb.append("（你提交了店铺资料修改，正在等待平台重审；当前线上仍是旧资料，照常营业）");
            }
            if (status == 0 && reviewStatus == 1) {
                sb.append("（审核已通过但店铺未启用，可到「我的店铺」页面检查是否还有未完成的步骤）");
            }
        }

        sb.append('\n').append(dataAsOfLine("审核进度以平台审核结果与「我的店铺」页面为准"));
        return truncate(sb.toString());
    }
}
