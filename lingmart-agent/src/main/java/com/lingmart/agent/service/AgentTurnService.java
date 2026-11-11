package com.lingmart.agent.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lingmart.agent.config.AgentProperties;
import com.lingmart.agent.dto.AgentCaller;
import com.lingmart.agent.dto.AgentChatRequest;
import com.lingmart.agent.dto.AgentToolCall;
import com.lingmart.agent.dto.AgentTurnResult;
import com.lingmart.agent.dto.HistoryEntry;
import com.lingmart.agent.dto.ToolExecutionResult;
import com.lingmart.agent.prompt.AgentPrompts;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 单轮对话编排核心：组装 System + 会话历史 + 当前用户消息（含操作回执）→ 知识检索注入 →
 * <b>自主循环</b>调用 LLM（工具 schema 已挂载但<b>框架内部自动执行关闭</b>）→ 产出「成文回答」
 * 或「待确认操作意图」。调用结束后将本轮 user/assistant 落盘到会话记忆。
 *
 * <p>所谓自主循环（见 {@link #runTurnLoop}）：模型可在一轮内连续调用<b>只读工具</b>，
 * 由服务端直接执行并把结果回灌给它，直到它给出成文回答或请求一个<b>写操作</b>——后者永不在这里
 * 执行，而是下发给用户确认。框架的自动执行必须关闭，否则写操作会被一并执行掉。</p>
 */
@Slf4j
@Service
public class AgentTurnService {

    private final ChatClient chatClient;
    private final ObjectMapper objectMapper;
    private final AgentProperties properties;
    private final ChatMemoryService memoryService;
    private final KnowledgeBaseService knowledgeBaseService;
    private final AgentAuditService auditService;
    private final ToolRegistry toolRegistry;
    private final ReadOnlyToolGuard readOnlyGuard;
    /** 预填开店申请草稿的准入闸门（v0.28）：拒掉"没东西可填"的空草稿 */
    private final PrefillGuard prefillGuard;
    /** 会话文档（用户上传的开店材料）：正文只在用户当轮附上时注入，不落会话历史 */
    private final AgentDocumentService documentService;
    /** 前置路由闸门（v0.30）：受理判定 + 覆盖式话术识别，都在模型之外完成 */
    private final PreRouteGuard preRouteGuard;
    private final RestClient gatewayClient;
    private final ExecutorService llmExecutor;
    private final MeterRegistry meterRegistry;

    /** 写操作工具（页面操作）：无请求级状态，构造一次复用。服务端只提供 schema，永不执行。 */
    private final List<ToolCallback> writeToolCallbacks;

    /**
     * 本轮用户侧没有可落盘内容时的占位文本。
     *
     * <p>存在的唯一目的是保住「一轮 = user + assistant」这条配对不变量——它是会话历史按轮裁剪的前提。
     * 内容刻意保持中性、无信息量，因为它会被写进历史并随上下文回灌给模型。</p>
     */
    private static final String EMPTY_USER_PLACEHOLDER = "（用户未输入文字）";

    /**
     * 前置闸门拦下元请求时的固定回复（v0.30）。
     *
     * <p>刻意<b>不复述命中了哪条规则</b>：那等于把规则表念给试探者听，方便他绕。
     * 内容上如实给出可用范围，让正常用户知道下一步该问什么。</p>
     */
    private static final String PRE_ROUTE_BLOCK_REPLY =
            "这个请求我处理不了～我是轻营平台的助手，只在这个范围内帮你：\n"
                    + "- 解答平台使用问题（开店、订单、退款、商品、账号等）\n"
                    + "- 查你自己的订单 / 店铺 / 商品\n"
                    + "- 引导你在页面上跳转或点击\n"
                    + "- 根据你上传的清单预填开店申请\n\n"
                    + "换个和轻营平台相关的问法就行，我随时在。";

    private final TypeReference<Map<String, Object>> argsType = new TypeReference<>() {
    };

    public AgentTurnService(ChatClient.Builder chatClientBuilder, ObjectMapper objectMapper,
                            AgentProperties properties, ChatMemoryService memoryService,
                            KnowledgeBaseService knowledgeBaseService, AgentAuditService auditService,
                            ToolRegistry toolRegistry, ReadOnlyToolGuard readOnlyGuard, PrefillGuard prefillGuard,
                            AgentDocumentService documentService, PreRouteGuard preRouteGuard,
                            RestClient agentGatewayRestClient, ExecutorService agentSseExecutor,
                            ObjectProvider<MeterRegistry> meterRegistryProvider) {
        this.chatClient = chatClientBuilder.build();
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.memoryService = memoryService;
        this.knowledgeBaseService = knowledgeBaseService;
        this.auditService = auditService;
        this.toolRegistry = toolRegistry;
        this.readOnlyGuard = readOnlyGuard;
        this.prefillGuard = prefillGuard;
        this.documentService = documentService;
        this.preRouteGuard = preRouteGuard;
        this.gatewayClient = agentGatewayRestClient;
        this.llmExecutor = agentSseExecutor;
        this.meterRegistry = meterRegistryProvider.getIfAvailable();
        // 页面操作工具：仅供模型生成 schema 与「请求」工具，实际由前端在用户确认后执行。
        // 只读工具不在这里——它们持有请求级身份（token/会话），必须每轮实例化，见 buildToolCallbacks。
        this.writeToolCallbacks = List.of(MethodToolCallbackProvider.builder()
                .toolObjects(new PageActionTools())
                .build()
                .getToolCallbacks());
    }

    /**
     * 处理一轮对话，返回文字或操作意图。
     */
    public AgentTurnResult processTurn(AgentChatRequest request, AgentCaller caller, ToolExecutionListener listener) {
        Long userId = caller.userId();
        String userRole = caller.userRole();
        String sessionId = request.sessionId();
        List<ToolExecutionResult> receipts = request.toolResults() == null ? List.of() : request.toolResults();

        // 1) 回执校验与消费 —— 必须先于「组装用户内容」：只有命中服务端下发记录（nonce 一致）的回执
        //    才被采信为“已确认并执行”，未命中的一律不采信、更不能作为既成事实进入模型上下文。
        ChatMemoryService.ConsumeResult consumed = memoryService.consumeReceipts(userId, sessionId, receipts);
        // 审计：命中/未命中的回执都记一条（未命中的是安全信号，排障与风控都要能一眼筛出来）
        auditService.recordReceipts(userId, sessionId, consumed.matched(), consumed.unmatched());

        AgentProperties.Constraint constraint = properties.getConstraint();
        boolean constraintOn = constraint.isEnabled();

        // 2) 前置路由闸门（v0.30）：在付出任何模型调用之前判定"这条请求要不要受理"。
        //    只看**用户自己写的话**——页面上下文/上传文档/工具结果里的字面匹配是正常内容，
        //    拿它们判定攻击会把普通商品标题误判成注入。
        PreRouteGuard.Decision route = preRouteGuard.judge(request.message());
        if (constraintOn && constraint.isPreRouteEnabled() && route.blocked()) {
            auditService.recordPreRouteBlocked(userId, sessionId, route.ruleId(), request.message());
            log.info("前置闸门拦下本轮（未调用模型）sessionId={} rule={}", sessionId, route.ruleId());
            // 刻意不落盘：这类文本不进会话历史，后续轮次也就不会把它当上下文回灌。
            // 代价是刷新后这段对话不显示——可以接受，它本来就不是一次正常的对话
            return AgentTurnResult.text(PRE_ROUTE_BLOCK_REPLY);
        }

        // 2.2) 角色重置（v0.30）：命中覆盖式话术（「忽略之前的指令」这类）时推进上下文地平线，
        //      此后不再把地平线之前的对话作为依据。**不拦**——同一句话既是注入的经典开场白，
        //      也是完全正常的纠正（"忽略上面那句，我改问订单"），拦它必然误伤。
        //      真正要防的是"让模型把先前立场当既定事实"，那由地平线解决。
        long horizon = constraintOn ? memoryService.contextResetAt(userId, sessionId) : 0L;
        boolean resetFired = constraintOn && constraint.isResetOnOverride() && route.reset();
        String coldStartNote = "";
        if (resetFired) {
            horizon = memoryService.markContextReset(userId, sessionId);
            auditService.recordContextReset(userId, sessionId, route.ruleId());
            coldStartNote = AgentPrompts.buildColdStartNote();
            log.info("命中覆盖式话术，已推进上下文地平线 sessionId={} rule={}", sessionId, route.ruleId());
        }

        // 3) 当前轮用户文本：输入 + 已采信的回执；未采信的另起一段说明，让模型知道它们不算数
        String userText = AgentPrompts.buildUserContent(request.message(), consumed.matched());
        String unmatchedNote = AgentPrompts.buildUnmatchedNote(consumed.unmatched().size());
        // 3.1) 本轮随消息附上的文档（若有）
        String documentNote = buildDocumentNote(request.documentId(), userId, sessionId);
        if (!StringUtils.hasText(userText) && !StringUtils.hasText(unmatchedNote)
                && !StringUtils.hasText(documentNote)) {
            return AgentTurnResult.empty();
        }

        // 3.5) 上一轮「单轮操作超限」纠偏提示：读取即消费（只影响紧随其后的一轮），且不写入会话记忆。
        //    刻意放在空轮早退之后——空轮不该把提示消费掉。
        int maxToolsPerTurn = Math.max(1, properties.getMaxToolsPerTurn());
        String overflowNote = AgentPrompts.buildOverflowNote(maxToolsPerTurn,
                memoryService.takeToolOverflow(userId, sessionId));

        // 3.6) 注意力锚定（v0.30）：把硬约束压成一小段，放在**每轮用户消息的末尾**。
        //    System Prompt 在上下文最前面，长对话里模型对它的注意力会衰减（越靠中间越容易被忽略）；
        //    消息末尾离生成位置最近，是成本最低的锚定位置（每轮约 150 字，只进模型上下文）。
        String digest = constraintOn && constraint.isAttentionDigest()
                ? AgentPrompts.buildConstraintDigest() : "";

        // 送给模型的版本 = 用户内容 + 冷启动提要 + 文档 + 系统提醒 + 约束提要；
        // 落盘仍用 userText（不含这些提醒），避免它们被当成"用户说过的话"存进历史
        String promptText = joinNotes(userText, coldStartNote, documentNote, unmatchedNote, overflowNote, digest);

        // 3.5) 会话内操作次数上限（防无限确认/循环）：每次“确认后执行”计一次，超限引导清空会话。
        //      只统计「已采信」的回执——否则伪造回执可以白白耗光用户的会话操作配额（配额是可耗尽资源）。
        boolean executedOp = consumed.matched().stream().anyMatch(ToolExecutionResult::success);
        if (executedOp) {
            long ops = memoryService.incrementToolOps(userId, sessionId);
            int limit = Math.max(1, properties.getMaxToolIterations());
            if (ops > limit) {
                return AgentTurnResult.text(
                        "你在这个会话里执行的页面操作已达上限（" + limit + " 次）。为避免误触或反复循环，"
                                + "建议点击浮窗顶部的「清空会话」重新开始；也可以直接告诉我你想做什么，我先用文字给你步骤。");
            }
        }

        // 4) 知识检索（失败自动降级为空）：用纯用户内容检索，不带系统提醒
        List<String> kbDocs = StringUtils.hasText(userText) ? knowledgeBaseService.search(userText) : List.of();
        String kbContext = String.join("\n\n", kbDocs);

        // 5) 组装消息：System(含页面上下文/知识/待确认操作) + 会话历史(按字符预算裁剪) + 当前用户消息
        List<Message> messages = new ArrayList<>();
        // 注入「消费后仍在挂起」的操作意图：页面刷新后用户说“继续”时模型仍能精确复述（挂起-恢复）。
        // 不再是“有回执就一律清空”——挂起意图现在由「命中即消费」驱动，未确认的仍是有效上下文。
        //
        // 例外：本轮刚触发重置时不注入。挂起意图是**此前**由模型生成的（其 purpose/elementText 又抄自
        // 用户可写的页面内容），立刻把它摆回模型面前，等于刚清干净又自己把旧立场端了上去。
        // 挂起态本身没被删，用户回头点那张卡片依然有效（回执校验走的是服务端下发记录，与这里无关）。
        List<AgentToolCall> pendingOps = resetFired ? List.of() : memoryService.pending(userId, sessionId);
        messages.add(new SystemMessage(AgentPrompts.buildSystem(
                userId, userRole, request.context(), kbContext, buildPendingNote(pendingOps))));
        for (HistoryEntry entry : trimHistory(userId, sessionId, horizon)) {
            if ("assistant".equals(entry.role())) {
                messages.add(new AssistantMessage(entry.content()));
            } else {
                messages.add(new UserMessage(entry.content()));
            }
        }
        messages.add(new UserMessage(promptText));

        // 6) 自主循环：携带工具 schema 但关闭框架的自动执行——模型只能“请求”工具。
        //    只读工具由服务端直接执行并回灌，直到模型给出成文回答，或请求一个「写操作」
        //    （后者不执行，下发给用户确认）。
        TurnLoopResult loop = runTurnLoop(messages, caller, sessionId, listener);
        if (loop.llmFailed()) {
            return AgentTurnResult.empty();
        }
        String text = loop.text() == null ? "" : loop.text().trim();

        // 6.5) 单轮写操作数量上限——服务端强制，不依赖提示词里的「一次只发起一个动作」（那是软约束）。
        //      只保留前 maxToolsPerTurn 个下发给用户确认，其余丢弃，并把目的说明留到下一轮的纠偏提示。
        //      目的：任何时刻用户面前只有一张待确认卡片，杜绝"多张卡并列时夹带一个恶意动作、被习惯性点确认"。
        List<AgentToolCall> tools = loop.writeTools();
        if (tools.size() > maxToolsPerTurn) {
            int requested = tools.size();
            List<AgentToolCall> discarded = tools.stream().skip(maxToolsPerTurn).toList();
            tools = tools.stream().limit(maxToolsPerTurn).toList();
            memoryService.recordToolOverflow(userId, sessionId,
                    discarded.stream().map(AgentToolCall::purpose).toList());
            log.warn("模型单轮发起 {} 个页面操作，已截断为 {} 个 sessionId={}",
                    requested, maxToolsPerTurn, sessionId);
        }

        // 7) 落盘会话记忆（成文文本优先；工具轮无成文时落一句待确认占位——只针对被保留下来的操作）
        persistTurn(userId, sessionId, userText, text, tools);

        // 8) 决策输出：本轮新发起的操作「追加」进挂起列表（appendPending）。
        //    追加而非覆盖，是为了让用户手上任何一张还没点的旧卡片都仍能匹配到自己的下发记录。
        //    挂起意图只由「命中即消费」（第 1 步）与 TTL / 会话淘汰移除；不再因"本轮是纯文字"而整体清空——
        //    那样会让用户点一张仍显示在界面上的卡片时收到"未匹配"。
        if (!tools.isEmpty()) {
            memoryService.appendPending(userId, sessionId, tools);
            // 审计：每张下发给用户的确认卡片一条，保证"用户看到过什么"可查
            auditService.recordToolProposed(userId, sessionId, tools, request.message());
            return StringUtils.hasText(text)
                    ? AgentTurnResult.toolsWithText(text, tools)
                    : AgentTurnResult.tools(tools);
        }
        return StringUtils.hasText(text) ? AgentTurnResult.text(text) : AgentTurnResult.empty();
    }

    // ================= 自主循环 =================

    /**
     * 一轮对话内可能发生多次模型调用：模型先调只读工具 → 服务端执行 → 把结果回灌 → 模型再决定。
     *
     * <p>为什么不用 Spring AI 的 {@code internalToolExecutionEnabled(true)} 自动执行：那会连
     * <b>写操作工具</b>（navigate_to / click_element）一起执行——而它们的执行必须由用户在前端点确认。
     * 所以这里关闭框架的执行、自己控制分流：只读的执行，写的攒下来交给确认流程。</p>
     *
     * <p>三道终止条件：模型给出成文回答 / 模型请求写操作 / 闸门用尽（次数或时长）。</p>
     */
    private TurnLoopResult runTurnLoop(List<Message> messages, AgentCaller caller, String sessionId,
                                       ToolExecutionListener listener) {
        AgentProperties.ReadOnly cfg = properties.getReadOnly();
        int maxCalls = Math.max(1, cfg.getMaxCallsPerTurn());
        long deadlineNanos = System.nanoTime() + Math.max(1000, cfg.getTurnBudgetMs()) * 1_000_000L;

        Map<String, ToolCallback> callbacks = buildToolCallbacks(caller, sessionId);
        int calls = 0;
        String text = "";
        boolean llmFailed = false;

        while (true) {
            long remainingMs = TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime());
            if (remainingMs <= 0) {
                // 时长用尽：禁用工具再问一次，逼它用已有信息作答（见下）
                text = finalizeWithBudgetExhausted(messages, sessionId, calls, text);
                break;
            }

            ChatResponse response;
            long startNanos = System.nanoTime();
            String llmResult;
            try {
                response = callModelWithTimeout(messages, callbacks, Math.min(properties.getLlmTimeoutMs(), remainingMs));
                llmResult = "success";
            } catch (LlmTimeoutException e) {
                llmResult = "timeout";
                response = null;
            } catch (Exception e) {
                llmResult = "error";
                log.error("Agent 调用 LLM 失败 sessionId={}", sessionId, e);
                response = null;
            }
            long costMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
            recordLlmCall(sessionId, llmResult, costMs, response);

            if (response == null || response.getResult() == null) {
                if (llmResult.equals("timeout")) {
                    log.warn("Agent LLM 调用超时 sessionId={} 耗时 {}ms", sessionId, costMs);
                    text = "抱歉，模型响应超时了，请稍后再试或换个问法。";
                } else {
                    llmFailed = true;
                }
                break;
            }

            AssistantMessage output = response.getResult().getOutput();
            String turnText = output.getText() == null ? "" : output.getText().trim();
            List<AssistantMessage.ToolCall> toolCalls = output.getToolCalls();
            if (!StringUtils.hasText(turnText) && CollectionUtils.isEmpty(toolCalls)) {
                log.warn("Agent 模型返回为空 sessionId={}", sessionId);
                llmFailed = true;
                break;
            }
            if (StringUtils.hasText(turnText)) {
                text = turnText;
            }
            if (CollectionUtils.isEmpty(toolCalls)) {
                break;
            }

            // 分流：服务端可执行的（只读）与需要用户确认的（写）
            List<AssistantMessage.ToolCall> readOnly = toolCalls.stream()
                    .filter(c -> toolRegistry.isServerExecutable(c.name())).toList();
            List<AssistantMessage.ToolCall> write = toolCalls.stream()
                    .filter(c -> !toolRegistry.isServerExecutable(c.name())).toList();

            if (!write.isEmpty()) {
                // 混轮（同轮既有只读又有写）：只保留写操作，丢弃只读部分。
                // 代价是不执行那几个查询，但换来"每轮只给用户一张卡片"这条不可让步的约束，
                // 且模型在下一轮（用户确认后）仍可重新发起查询——它自己的意图文本已落进历史。
                if (!readOnly.isEmpty()) {
                    log.warn("模型同轮混合了 {} 个只读查询与 {} 个写操作，已只保留写操作 sessionId={}",
                            readOnly.size(), write.size(), sessionId);
                }
                // 预填草稿先过闸门：空草稿不发卡片，把原因回灌给模型让它自己修正后重来
                // （与只读工具闸门同一思路；其它写工具 navigate/click 原样放行）
                PrefillGuard.Result screened = prefillGuard.screen(write);
                if (screened.accepted().isEmpty()) {
                    log.warn("预填草稿未通过闸门，回灌纠偏 sessionId={}", sessionId);
                    messages.add(AssistantMessage.builder().content(turnText).toolCalls(toolCalls).build());
                    messages.add(ToolResponseMessage.builder().responses(screened.rejected()).build());
                    continue;
                }
                text = turnText;
                return new TurnLoopResult(text,
                        screened.accepted().stream().map(this::toAgentToolCall).toList(), false);
            }

            // 只剩只读：检查闸门后执行
            if (calls + readOnly.size() > maxCalls) {
                log.warn("单轮只读工具调用将超过上限 {}，停止循环 sessionId={}", maxCalls, sessionId);
                text = finalizeWithBudgetExhausted(messages, sessionId, calls, text);
                break;
            }

            messages.add(AssistantMessage.builder().content(turnText).toolCalls(toolCalls).build());
            List<ToolResponseMessage.ToolResponse> responses = new ArrayList<>();
            for (AssistantMessage.ToolCall call : readOnly) {
                ToolCallback callback = callbacks.get(call.name());
                if (callback == null) {
                    // 工具名不在注册表里：回执告知模型，让它换一个（不抛异常，避免整轮失败）
                    responses.add(new ToolResponseMessage.ToolResponse(call.id(), call.name(),
                            "该工具不可用，请改用其他方式回答用户。"));
                    continue;
                }
                listener.onToolRun(call.id(), call.name(), toolRegistry.labelOf(call.name()));
                String result;
                try {
                    result = callback.call(call.arguments());
                } catch (Exception e) {
                    log.warn("只读工具执行异常 name={} sessionId={}：{}", call.name(), sessionId, e.getMessage());
                    result = "查询失败，请稍后再试或引导用户去对应页面查看。";
                }
                // 数据时间从工具文本里反解（见 AbstractReadOnlyTool#parseDataAsOf）：缓存命中时它带回的是
                // 首次查询的时间而非"此刻"——否则用户会把缓存里的旧数据当成实时数据
                listener.onToolResult(call.id(), call.name(), summarize(result),
                        AbstractReadOnlyTool.parseDataAsOf(result));
                // 工具返回同样是外部数据（订单号、商品标题等可能间接含用户可写内容），回灌给模型前包进边界标签
                responses.add(new ToolResponseMessage.ToolResponse(call.id(), call.name(),
                        AgentPrompts.wrapAsData(AgentPrompts.TAG_TOOL_RESULT, result)));
                calls++;
            }
            messages.add(ToolResponseMessage.builder().responses(responses).build());
        }

        return new TurnLoopResult(text, List.of(), llmFailed);
    }

    /**
     * 闸门用尽时的收尾：**禁用工具**再问模型一次，逼它用已获得的信息作答。
     *
     * <p>不加这一步的话，用户等到的是一段没有结论的文字（模型上一轮的文本只是"我去查一下"）。
     * 禁用工具是保证终止的关键——不禁用的话模型很可能又请求一次查询，循环就停不下来。</p>
     */
    private String finalizeWithBudgetExhausted(List<Message> messages, String sessionId,
                                               int executedCalls, String fallbackText) {
        messages.add(new UserMessage("【系统提醒】本轮的工具调用次数或时间预算已用尽。"
                + "请立刻基于已经获得的信息作答，不要再请求任何工具；"
                + "如果查到的东西不足以回答用户，就如实说明只查到了部分，并建议用户去对应页面查看。"));
        try {
            ChatResponse response = callModelWithTimeout(messages, Map.of(), properties.getLlmTimeoutMs());
            if (response != null && response.getResult() != null) {
                String t = response.getResult().getOutput().getText();
                if (StringUtils.hasText(t)) {
                    return t.trim();
                }
            }
        } catch (Exception e) {
            log.warn("收尾作答失败 sessionId={}：{}", sessionId, e.getMessage());
        }
        // 收尾也失败：至少让用户知道"查到过东西但没整理出来"，别让他以为什么都没发生
        if (executedCalls > 0) {
            return "我查到了一部分信息，但没能整理成完整回答，请稍后再问一次。";
        }
        return StringUtils.hasText(fallbackText) ? fallbackText : "抱歉，我暂时无法给出可靠回答，请换个问法再试。";
    }

    /** 为前端结果卡片抽取一句话摘要（取工具返回文本的首行）。 */
    private static String summarize(String toolResult) {
        if (!StringUtils.hasText(toolResult)) {
            return "查询完成";
        }
        int idx = toolResult.indexOf('\n');
        String first = idx > 0 ? toolResult.substring(0, idx) : toolResult;
        return first.length() > 180 ? first.substring(0, 180) + "…" : first;
    }

    /**
     * 构造本轮可用的工具回调：写操作（静态）+ 只读（持有本轮身份，必须每轮新建）。
     *
     * <p>只读工具是按轮实例化的——它持有调用者的 token 与会话 ID，是请求级状态；
     * 做成单例会让并发请求互相串身份（最严重的后果是 A 的查询用 B 的 token 执行）。</p>
     *
     * <p>新增只读工具时<b>两处都要改</b>：这里的实例化清单，以及 {@code ToolRegistry} 构造函数里的
     * 类别注册。漏了后者，工具会落到 {@code isWrite} 的 fail-safe 分支——按写操作处理、
     * 每次都要用户确认（方向安全，但会被用户感知为"这个查询怎么突然要确认"）。
     * 漏了这里则是工具根本不存在。</p>
     */
    private Map<String, ToolCallback> buildToolCallbacks(AgentCaller caller, String sessionId) {
        Map<String, ToolCallback> byName = new LinkedHashMap<>();
        for (ToolCallback callback : writeToolCallbacks) {
            byName.put(callback.getToolDefinition().name(), callback);
        }
        if (properties.getReadOnly().isEnabled()) {
            Object[] readOnlyTools = {
                    new OrderQueryTools(gatewayClient, caller, sessionId, properties,
                            readOnlyGuard, auditService, objectMapper),
                    new ShopQueryTools(gatewayClient, caller, sessionId, properties,
                            readOnlyGuard, auditService, objectMapper),
                    new ProductQueryTools(gatewayClient, caller, sessionId, properties,
                            readOnlyGuard, auditService, objectMapper),
                    new SearchQueryTools(gatewayClient, caller, sessionId, properties,
                            readOnlyGuard, auditService, objectMapper)
            };
            for (ToolCallback callback : MethodToolCallbackProvider.builder()
                    .toolObjects(readOnlyTools)
                    .build()
                    .getToolCallbacks()) {
                byName.put(callback.getToolDefinition().name(), callback);
            }
        }
        return byName;
    }

    /**
     * 一轮自主循环的结果。
     *
     * <p>不返回"已执行的只读调用"清单：那些调用已在 {@code OrderQueryTools} 内各自记了审计
     * （{@code tool_run}），此处再带一份就是无人读取的死数据。</p>
     */
    private record TurnLoopResult(
            /** 最终成文文本（可能为空，例如只发起了写操作） */
            String text,
            /** 需要用户确认的写操作（已转为前端可渲染形态） */
            List<AgentToolCall> writeTools,
            /** LLM 彻底失败（无任何可用输出） */
            boolean llmFailed) {
    }

    /**
     * 取本轮附上的文档并拼成提示词块；没附上、或 documentId 与会话中现存的那份对不上时返回空串。
     *
     * <p>对不上就静默不注入：前端回传的是它收到的那一份，若期间用户又传了别的文件（覆盖了存储），
     * 旧 id 不该再捞出新的正文来。宁可不注入（模型会反问缺什么），也不要注入一份用户没预期的东西。</p>
     */
    private String buildDocumentNote(String documentId, Long userId, String sessionId) {
        if (!StringUtils.hasText(documentId)) {
            return "";
        }
        AgentDocumentService.StoredDocument doc = documentService.load(userId, sessionId, documentId);
        if (doc == null) {
            log.info("本轮携带的文档已不存在或与当前会话不匹配，已忽略 sessionId={}", sessionId);
            return "";
        }
        return AgentPrompts.buildDocumentNote(doc.name(), doc.summary(), doc.text(), doc.truncated());
    }

    /** 把用户内容与若干条「系统提醒」拼成最终送给模型的本轮消息（空项自动跳过，项间空行分隔）。 */
    private String joinNotes(String userText, String... notes) {
        StringBuilder sb = new StringBuilder(userText == null ? "" : userText);
        for (String note : notes) {
            if (StringUtils.hasText(note)) {
                if (!sb.isEmpty()) {
                    sb.append("\n\n");
                }
                sb.append(note);
            }
        }
        return sb.toString();
    }

    /**
     * 会话历史按<b>轮</b>裁剪：从最新一轮往前整轮累加，超出 {@code max-context-chars} 即丢弃更旧的轮。
     *
     * <p><b>为什么以轮为单位、而不是以单条消息为单位</b>：一轮 = 一条 user 消息 + 它引出的 assistant 回复。
     * 按消息裁会把这一对从中间切开，模型于是看到"没有提问的回答"；工具轮更危险——
     * user 侧是「页面操作执行回执」、assistant 侧是「我准备执行…（等待你确认）」，
     * 丢掉前半句会让模型以为那次操作<b>已经确认执行过了</b>，进而编造执行结果。</p>
     *
     * <p>最新一轮<b>无条件</b>保留（即使它自己就超预算）：它承载着用户此刻的问题，丢了整轮对话就没意义了。</p>
     *
     * @param horizon 上下文地平线（v0.30）：早于它的历史条目直接排除（0 = 无地平线，不过滤）。
     *                过滤发生在分组之前——先滤掉整轮，就不会出现"半个轮"被当成一轮的错位
     */
    private List<HistoryEntry> trimHistory(Long userId, String sessionId, long horizon) {
        List<HistoryEntry> history = memoryService.recent(userId, sessionId);
        if (horizon > 0) {
            int before = history.size();
            history = history.stream().filter(entry -> entry.ts() >= horizon).toList();
            if (history.size() < before) {
                log.info("上下文地平线生效，排除 {} 条重置前的历史 sessionId={}", before - history.size(), sessionId);
            }
        }
        List<List<HistoryEntry>> rounds = groupByRound(history);
        int budget = Math.max(1000, properties.getMaxContextChars());
        List<List<HistoryEntry>> keep = new ArrayList<>();
        int used = 0;
        for (int i = rounds.size() - 1; i >= 0; i--) {
            List<HistoryEntry> round = rounds.get(i);
            int weight = weightOf(round);
            if (!keep.isEmpty() && used + weight > budget) {
                break;
            }
            keep.add(0, round);
            used += weight;
        }
        if (keep.size() < rounds.size()) {
            log.info("会话历史超出上下文预算，按轮保留 {} / {} 轮 sessionId={}", keep.size(), rounds.size(), sessionId);
        }
        List<HistoryEntry> result = new ArrayList<>();
        for (List<HistoryEntry> round : keep) {
            result.addAll(round);
        }
        return result;
    }

    /**
     * 把历史切成「轮」：每遇到一条 user 消息就开新一轮，其后直到下一条 user 之前的所有消息都归它。
     *
     * <p>开头的孤立 assistant 消息（升级前落盘不对称留下的旧数据）自成一组：
     * 它没有提问可依附，作为独立一轮参与裁剪即可，不会破坏后续轮的对齐。</p>
     */
    private static List<List<HistoryEntry>> groupByRound(List<HistoryEntry> history) {
        List<List<HistoryEntry>> rounds = new ArrayList<>();
        for (HistoryEntry entry : history) {
            if (rounds.isEmpty() || "user".equals(entry.role())) {
                rounds.add(new ArrayList<>());
            }
            rounds.get(rounds.size() - 1).add(entry);
        }
        return rounds;
    }

    /** 一轮的字符权重（内容为空按 0 计）。 */
    private static int weightOf(List<HistoryEntry> round) {
        int weight = 0;
        for (HistoryEntry entry : round) {
            weight += entry.content() == null ? 0 : entry.content().length();
        }
        return weight;
    }

    /** 把“待确认操作”转成给模型看的一行行摘要（含可复用的 route/elementId）。 */
    private String buildPendingNote(List<AgentToolCall> tools) {
        if (CollectionUtils.isEmpty(tools)) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (AgentToolCall t : tools) {
            if (sb.length() > 0) {
                sb.append("\n");
            }
            sb.append("- 动作：").append(t.action() == null ? t.name() : t.action())
                    .append("；目的：").append(textOr(t.purpose(), "未说明"));
            String args = describeArgs(t.arguments());
            if (!args.isEmpty()) {
                sb.append("；参数：").append(args);
            }
        }
        return sb.toString();
    }

    private String describeArgs(Map<String, Object> args) {
        if (CollectionUtils.isEmpty(args)) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (String key : List.of("route", "elementId", "elementText")) {
            Object v = args.get(key);
            if (v != null && StringUtils.hasText(String.valueOf(v))) {
                if (sb.length() > 0) {
                    sb.append(", ");
                }
                sb.append(key).append('=').append(v);
            }
        }
        return sb.toString();
    }

    private String textOr(String v, String fallback) {
        return StringUtils.hasText(v) ? v : fallback;
    }

    /**
     * 带超时地调用模型：在独立线程执行阻塞调用，超时即取消并抛出超时异常（友好降级）。
     *
     * <p>{@code internalToolExecutionEnabled(false)} 是关键：模型只能"请求"工具，执行由我们控制。</p>
     *
     * @param callbacks 本轮可用的工具（只读工具按轮实例化，故不能复用字段）
     * @param timeoutMs 本次调用允许的时长——自主循环传的是「总预算与本轮剩余时间的较小值」，
     *                  否则单次调用就可能吃掉整个循环预算
     */
    private ChatResponse callModelWithTimeout(List<Message> messages, Map<String, ToolCallback> callbacks,
                                             long timeoutMs) throws LlmTimeoutException, Exception {
        ToolCallingChatOptions.Builder optionsBuilder = ToolCallingChatOptions.builder()
                .internalToolExecutionEnabled(false);
        if (!callbacks.isEmpty()) {
            optionsBuilder.toolCallbacks(new ArrayList<>(callbacks.values()));
        }
        ToolCallingChatOptions options = optionsBuilder.build();

        Callable<ChatResponse> task = () -> chatClient.prompt()
                .messages(messages)
                .options(options)
                .call()
                .chatResponse();
        long effectiveTimeoutMs = Math.max(1000, timeoutMs);
        Future<ChatResponse> future = llmExecutor.submit(task);
        try {
            return future.get(effectiveTimeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true); // 尽力中断底层调用（虚拟线程，即使未中断也不占请求线程）
            throw new LlmTimeoutException(e);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof Exception cause) {
                throw cause;
            }
            throw e;
        }
    }

    /**
     * 记录一次 LLM 调用：耗时/结果/Token 数。
     *
     * <p>Token 数<b>同时</b>输出一行 INFO 日志与 Micrometer 指标。原因：指标只在
     * {@code /actuator/prometheus} 暴露时才读得到，而该端点在部分部署里并不可用；
     * Token 数（评估知识注入模式成本的关键数据）必须有一条不依赖管理端点的可读通道。</p>
     */
    private void recordLlmCall(String sessionId, String result, long costMs, ChatResponse response) {
        Integer total = null;
        Integer prompt = null;
        Integer completion = null;
        if (response != null && response.getMetadata() != null) {
            Usage usage = response.getMetadata().getUsage();
            if (usage != null) {
                total = usage.getTotalTokens();
                prompt = usage.getPromptTokens();
                completion = usage.getCompletionTokens();
            }
        }
        log.info("Agent LLM 调用 sessionId={} 结果={} 耗时={}ms promptTokens={} completionTokens={} totalTokens={}",
                sessionId, result, costMs, prompt, completion, total);
        if (meterRegistry == null) {
            return;
        }
        try {
            meterRegistry.counter("agent.llm.calls", "result", result).increment();
            meterRegistry.timer("agent.llm.duration", "result", result)
                    .record(costMs, TimeUnit.MILLISECONDS);
            if (total != null && total > 0) {
                meterRegistry.counter("agent.llm.tokens", "type", "total").increment(total);
            }
            if (prompt != null && prompt > 0) {
                meterRegistry.counter("agent.llm.tokens", "type", "prompt").increment(prompt);
            }
            if (completion != null && completion > 0) {
                meterRegistry.counter("agent.llm.tokens", "type", "completion").increment(completion);
            }
        } catch (Exception ignore) {
            // 指标记录失败不影响主流程
        }
    }

    /** 标记模型调用超时的内部异常。 */
    private static final class LlmTimeoutException extends Exception {
        LlmTimeoutException(Throwable cause) {
            super(cause);
        }
    }

    /** 把模型原生工具调用映射为前端可渲染的操作意图（脱敏 + 动作归一化 + 生成回执校验串）。 */
    private AgentToolCall toAgentToolCall(AssistantMessage.ToolCall call) {
        Map<String, Object> args = parseArguments(call.arguments());
        String purpose = args.get("purpose") instanceof String p && StringUtils.hasText(p)
                ? p
                : "为你执行一次页面操作";
        String action = switch (call.name()) {
            case "navigate_to" -> "navigate";
            case "click_element" -> "click";
            // v0.28：预填开店申请。它不是 DOM 操作，前端不交给 domExecutor 执行，
            // 而是把 draft 写进 store、跳转到申请页由页面消费（见 CopilotWidget.confirmTool）
            case "prefill_shop_application" -> "prefill";
            default -> call.name();
        };
        // nonce 由服务端生成，随 SSE 下发给前端、由前端在回执中原样带回；服务端凭它确认"该回执确实对应
        // 我下发过的这个意图"。刻意不放进模型可见的上下文（buildPendingNote 只渲染动作/目的/参数），
        // 也不复用模型给的 toolCallId——后者由模型生成、可预测，不足以作为校验凭据。
        return new AgentToolCall(call.id(), call.name(), action, args, purpose, UUID.randomUUID().toString());
    }

    private Map<String, Object> parseArguments(String argumentsJson) {
        if (!StringUtils.hasText(argumentsJson)) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(argumentsJson, argsType);
        } catch (Exception e) {
            log.warn("解析工具参数失败，回退为原文: {}", e.getMessage());
            return Map.of("raw", argumentsJson);
        }
    }

    /**
     * 落盘本轮 user/assistant 消息。
     *
     * <p>注意 {@code userText} 传的是<b>不含纠偏提示</b>的原始用户内容——纠偏提示只作用于当轮 Prompt，
     * 写入历史会永久占用上下文且事后成为噪声。</p>
     *
     * @param tools 实际下发的操作（已按单轮上限截断），用于工具轮生成占位描述
     */
    private void persistTurn(Long userId, String sessionId, String userText, String text,
                             List<AgentToolCall> tools) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        // 用户侧为空也要落一条占位，不能跳过：assistant 消息必然会被写进去，只写一半会让历史里
        // 出现"没有提问的回答"，而按轮裁剪（trimHistory）正是以 user 消息作为每轮的起点。
        // 可达路径：用户点了一张已失效的确认卡片（回执 nonce 匹配不上服务端下发记录）且没有再输入文字——
        // 此时 unmatchedNote 非空、userText 为空，恰是本方法最容易被忽略的输入组合。
        memoryService.pushUser(userId, sessionId,
                StringUtils.hasText(userText) ? userText : EMPTY_USER_PLACEHOLDER);
        // 落盘内容做长度钳制（与输入同量级），避免超长回答把历史撑大，配合 trimHistory 一起控制上下文规模
        int cap = Math.max(100, properties.getMaxMessageLength());
        String assistant = StringUtils.hasText(text) ? clamp(text, cap) : buildToolPlaceholder(tools);
        memoryService.pushAssistant(userId, sessionId, assistant);
    }

    private String clamp(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    /** 工具轮次无成文时，落盘一句待确认占位，保证历史会话读起来自然。 */
    private String buildToolPlaceholder(List<AgentToolCall> tools) {
        if (CollectionUtils.isEmpty(tools)) {
            return "(无内容)";
        }
        List<String> purposes = tools.stream()
                .map(t -> textOr(t.purpose(), "执行一次页面操作"))
                .toList();
        return "我准备执行页面操作：" + String.join("；", purposes) + "（等待你确认）";
    }
}
