package com.lingmart.agent.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lingmart.agent.config.AgentProperties;
import com.lingmart.agent.dto.AgentCaller;
import com.lingmart.agent.dto.AgentChatRequest;
import com.lingmart.agent.dto.AgentToolCall;
import com.lingmart.agent.dto.AgentTurnResult;
import com.lingmart.agent.dto.HistoryEntry;
import com.lingmart.agent.service.AgentAuditService;
import com.lingmart.agent.service.AgentTurnService;
import com.lingmart.agent.service.ChatMemoryService;
import com.lingmart.agent.service.JwtAuthService;
import com.lingmart.agent.service.ToolExecutionListener;
import com.lingmart.common.exception.BizException;
import com.lingmart.common.result.R;
import com.lingmart.common.util.RedisRateLimiter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Semaphore;

/**
 * Agent 对话接口（SSE 流式）。
 *
 * <p>协议事件：{@code message_start / content_delta / tool_run / tool_result / tool_call /
 * message_end / error}。其中 {@code tool_run}、{@code tool_result} 是<b>只读工具</b>由服务端
 * 自主执行时的进度事件（见 {@code progressListener}）；{@code tool_call} 则是<b>写操作</b>意图，
 * 需用户在前端确认后才执行。经网关 POST /api/agent/chat 路由至此（produces=text/event-stream）。</p>
 *
 * <p>用户身份从 {@code Authorization} 头<b>自行验签 JWT</b> 得到（见 {@link JwtAuthService}），
 * 不信任网关透传的 {@code X-User-Id}，也不入请求体。</p>
 */
@Slf4j
@RestController
public class AgentChatController {

    /** 每段下发的文本长度 */
    private static final int CHUNK_SIZE = 12;
    /** SSE 连接超时（毫秒） */
    private static final long SSE_TIMEOUT_MS = 90_000;
    private final AgentTurnService turnService;
    private final ChatMemoryService memoryService;
    private final AgentAuditService auditService;
    private final JwtAuthService jwtAuthService;
    private final AgentProperties properties;
    private final RedisRateLimiter rateLimiter;
    private final ExecutorService sseExecutor;
    private final ObjectMapper objectMapper;
    /** SSE 并发信号量：控制同时在途的 LLM 调用数（见 AgentInfraConfig），超出快速拒绝 */
    private final Semaphore sseLimiter;

    public AgentChatController(AgentTurnService turnService, ChatMemoryService memoryService,
                               AgentAuditService auditService, JwtAuthService jwtAuthService,
                               AgentProperties properties, RedisRateLimiter rateLimiter,
                               ExecutorService agentSseExecutor, ObjectMapper objectMapper,
                               Semaphore agentSseLimiter) {
        this.turnService = turnService;
        this.memoryService = memoryService;
        this.auditService = auditService;
        this.jwtAuthService = jwtAuthService;
        this.properties = properties;
        this.rateLimiter = rateLimiter;
        this.sseExecutor = agentSseExecutor;
        this.objectMapper = objectMapper;
        this.sseLimiter = agentSseLimiter;
    }

    /**
     * 单轮对话（SSE）。说明：操作执行不在本服务完成——返回 tool_call 后由前端确认并执行，
     * 回执以带 toolResults 的新一轮请求回到本接口继续。
     */
    @PostMapping(value = "/api/agent/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chat(@RequestBody AgentChatRequest request,
                           @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        emitter.onError(e -> log.warn("Agent SSE 客户端断开", e));
        emitter.onTimeout(() -> {
            log.warn("Agent SSE 连接超时");
            emitter.complete();
        });
        // 并发信号量：拿不到许可立即返回错误事件，避免打满虚拟线程/LLM API
        if (!sseLimiter.tryAcquire()) {
            sseExecutor.execute(() -> {
                try {
                    sendError(emitter, "服务正忙，请稍后再试");
                } finally {
                    emitter.complete();
                }
            });
            return emitter;
        }
        sseExecutor.execute(() -> handleTurn(emitter, request, authorization));
        return emitter;
    }

    private void handleTurn(SseEmitter emitter, AgentChatRequest request, String authorization) {
        String sessionId = StringUtils.hasText(request.sessionId()) ? request.sessionId() : UUID.randomUUID().toString();
        String messageId = UUID.randomUUID().toString();
        try {
            // —— 身份校验（最先做）：自己验 JWT，不信网关透传的 X-User-Id（见 JwtAuthService）——
            AgentCaller caller = jwtAuthService.resolve(authorization);
            Long userId = caller.userId();

            // —— 前置校验 & 限流 ——
            String message = request.message() == null ? "" : request.message().trim();
            boolean hasResults = request.toolResults() != null && !request.toolResults().isEmpty();
            if (!hasResults && !StringUtils.hasText(message)) {
                sendError(emitter, "请输入你的问题或操作诉求～");
                return;
            }
            if (message.length() > properties.getMaxMessageLength()) {
                sendError(emitter, "消息过长，请精简到 " + properties.getMaxMessageLength() + " 字以内");
                return;
            }
            // 限流键固定按 userId：旧版在身份缺失时回落到 sessionId 维度，而 sessionId 由前端自由提供，
            // 轮换 sessionId 即可绕过每分钟限流——身份校验前置后此绕过路径已不存在。
            if (!rateLimiter.allow("agent-chat:u" + userId, properties.getRatePerUserMinute(), 60_000L)) {
                sendError(emitter, "操作太频繁啦，请一分钟后再试");
                return;
            }

            send(emitter, "message_start", Map.of("messageId", messageId, "sessionId", sessionId));

            AgentTurnResult result = turnService.processTurn(request, caller,
                    progressListener(emitter, sessionId));
            // 审计本轮最终形态（显式映射而非 name().toLowerCase()，保证与表注释里的取值一致）
            auditService.recordTurnCompleted(userId, sessionId, switch (result.kind()) {
                case TEXT -> "text";
                case TOOL -> "tool";
                case EMPTY -> "empty";
            }, null);
            switch (result.kind()) {
                case TOOL -> {
                    streamText(emitter, result.text());
                    for (AgentToolCall tool : result.tools()) {
                        // 用 LinkedHashMap 而非 Map.of：Map.of 不接受 null 值，而历史挂起态里的 nonce 可能为空
                        Map<String, Object> payload = new LinkedHashMap<>();
                        payload.put("toolCallId", tool.toolCallId());
                        payload.put("name", tool.name());
                        payload.put("action", tool.action());
                        payload.put("arguments", tool.arguments() == null ? Map.of() : tool.arguments());
                        payload.put("purpose", tool.purpose());
                        // nonce：前端确认时须原样回传，服务端据此校验该回执确实对应当前挂起的意图（见 consumeReceipts）
                        payload.put("nonce", tool.nonce() == null ? "" : tool.nonce());
                        send(emitter, "tool_call", payload);
                    }
                    send(emitter, "message_end", Map.of());
                }
                case TEXT -> {
                    streamText(emitter, result.text());
                    send(emitter, "message_end", Map.of());
                }
                case EMPTY -> sendError(emitter, "抱歉，我暂时无法给出可靠回答，请换个问法或稍后再试");
                default -> sendError(emitter, "抱歉，出现了未知错误");
            }
        } catch (BizException e) {
            // 业务级拒绝（当前仅身份校验）：把原因直接回给前端，不吞成「系统繁忙」。
            // 注意：本方法运行在 sseExecutor 线程上，抛出的异常到不了 GlobalExceptionHandler，必须就地转成 error 事件。
            log.warn("Agent 对话请求被拒绝 sessionId={}：{}", sessionId, e.getMessage());
            sendError(emitter, e.getMessage());
        } catch (Exception e) {
            log.error("Agent 对话处理异常 sessionId={}", sessionId, e);
            sendError(emitter, "服务开小差了，请稍后再试");
        } finally {
            sseLimiter.release();
            emitter.complete();
        }
    }

    /**
     * 该用户最近活跃的会话 ID（没有会话时返回 {@code data: null}）。
     *
     * <p>用于前端 localStorage 里没有 sessionId 时接回上次对话——换设备、清了浏览器数据、
     * 无痕窗口都属于这种情况，而上下文本来还在 Redis 的 TTL 内。身份取自 JWT，故只能取到自己的会话。</p>
     *
     * <p>路径段数与其它会话接口不同（4 段 vs 5 段），故与 {@code /session/{sessionId}/history} 不冲突。</p>
     */
    @GetMapping("/api/agent/session/latest")
    public R<String> latestSession(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        return R.ok(memoryService.latestSession(jwtAuthService.resolve(authorization).userId()));
    }

    /** 最近会话历史（面板重开恢复用）。记忆按 userId+sessionId 隔离，身份取自 JWT。 */
    @GetMapping("/api/agent/session/{sessionId}/history")
    public R<List<HistoryEntry>> history(
            @PathVariable String sessionId,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        return R.ok(memoryService.recent(jwtAuthService.resolve(authorization).userId(), sessionId));
    }

    /** 清空会话上下文。 */
    @DeleteMapping("/api/agent/session/{sessionId}")
    public R<Void> clearSession(
            @PathVariable String sessionId,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        memoryService.clear(jwtAuthService.resolve(authorization).userId(), sessionId);
        return R.ok(null);
    }

    /**
     * 查询会话“待确认操作”（挂起态）。页面刷新后浮窗可据此把未执行完的操作卡片恢复出来（含原参数），
     * 实现「挂起 - 恢复」而非丢失上下文。
     */
    @GetMapping("/api/agent/session/{sessionId}/pending")
    public R<List<AgentToolCall>> pending(
            @PathVariable String sessionId,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        return R.ok(memoryService.pending(jwtAuthService.resolve(authorization).userId(), sessionId));
    }

    // ---------------- 只读工具进度事件 ----------------

    /**
     * 构造只读工具的进度监听器：把服务端自主执行的查询过程转成 SSE 事件。
     *
     * <p>回调内**自行吞掉发送异常**：客户端断开不该打断服务端的自主循环——否则一个关掉的浮窗
     * 会让正在进行的查询半途而废，留下一半状态。</p>
     */
    private ToolExecutionListener progressListener(SseEmitter emitter, String sessionId) {
        return new ToolExecutionListener() {
            @Override
            public void onToolRun(String toolCallId, String name, String label) {
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("toolCallId", toolCallId);
                payload.put("name", name);
                payload.put("label", label);
                sendQuietly(emitter, "tool_run", payload, sessionId);
            }

            @Override
            public void onToolResult(String toolCallId, String name, String summary, LocalDateTime dataAsOf) {
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("toolCallId", toolCallId);
                payload.put("name", name);
                payload.put("summary", summary);
                // 数据时间戳：由 AgentTurnService 从工具结果文本里反解（见 AbstractReadOnlyTool#parseDataAsOf），
                // 缓存命中时是首次查询时间。为 null 表示该结果没有时间语义（失败文案等），
                // 前端据此整行不展示——绝不回落成"当前时间"，那会让旧数据看起来是实时的
                payload.put("dataAsOf", dataAsOf == null ? null : dataAsOf.toString());
                sendQuietly(emitter, "tool_result", payload, sessionId);
            }
        };
    }

    /** 发送进度类事件；失败只记 debug，不向上抛（见 progressListener 的说明）。 */
    private void sendQuietly(SseEmitter emitter, String event, Map<String, Object> payload, String sessionId) {
        try {
            send(emitter, event, payload);
        } catch (Exception e) {
            log.debug("{} 事件发送失败（客户端可能已断开）sessionId={}：{}", event, sessionId, e.getMessage());
        }
    }

    // ---------------- SSE 工具 ----------------

    private void streamText(SseEmitter emitter, String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        // 打字机间隔由配置控制：默认 0=实时一次性输出（不做人为延迟）
        long delay = Math.max(0, properties.getTypingDelayMs());
        for (int i = 0; i < text.length(); i += CHUNK_SIZE) {
            String delta = text.substring(i, Math.min(text.length(), i + CHUNK_SIZE));
            send(emitter, "content_delta", Map.of("delta", delta));
            if (delay > 0) {
                sleepQuietly(delay);
            }
        }
    }

    private void sendError(SseEmitter emitter, String message) {
        try {
            send(emitter, "error", Map.of("message", message));
        } catch (Exception ignore) {
            // 客户端可能已断开
        }
    }

    private void send(SseEmitter emitter, String event, Map<String, ?> data) {
        try {
            emitter.send(SseEmitter.event()
                    .name(event)
                    .data(objectMapper.writeValueAsString(data)));
        } catch (IOException e) {
            throw new SseSendException(e);
        }
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // 身份解析已下沉到 JwtAuthService：Agent 自己验 JWT，不再信任网关透传的 X-User-Id（原因见该服务注释）。
    // 本控制器内不再有本地身份解析逻辑；身份从 Authorization 头解析后向下传 AgentCaller。

    /** 内部标记：客户端断开等 SSE 发送失败（由 handleTurn 统一收敛）。 */
    private static final class SseSendException extends RuntimeException {
        SseSendException(Throwable cause) {
            super(cause);
        }
    }
}
