package com.lingmart.agent.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lingmart.agent.config.AgentProperties;
import com.lingmart.agent.dto.AgentToolCall;
import com.lingmart.agent.dto.HistoryEntry;
import com.lingmart.agent.dto.ToolExecutionResult;
import com.lingmart.common.exception.BizException;
import com.lingmart.common.result.ResultCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Agent 会话记忆服务：Redis List 逐条存 {role, content, ts} JSON，实现“保留最近 N 条 + TTL 自动过期”。
 *
 * <p>Key 同时绑定 userId 与 sessionId（{@code agent:session:{userId}:{sessionId}}），杜绝跨用户用
 * sessionId 枚举窥探他人会话；另维护「会话内已执行页面操作次数」计数器，用于防无限确认。</p>
 *
 * <p><b>裁剪对齐到「轮」</b>：一轮 = 一条 user + 它引出的 assistant 回复。按条数硬裁会把这一对从中间
 * 切开，模型于是看到"没有提问的回答"（工具轮尤其危险，见 {@link #alignToRoundStart}）。
 * 存储侧这里只做兜底，组装 Prompt 时的字符预算裁剪由 {@code AgentTurnService#trimHistory} 按轮处理。</p>
 *
 * <p><b>不提供匿名会话</b>：userId 为 null 时一律 fail-closed（见 {@code uidPart}），
 * 不回落共享命名空间。身份来源与校验见 {@code JwtAuthService#resolve}。</p>
 *
 * <p>不做框架级 ChatMemory：每条 user/assistant 均为纯文本，操作回执折叠进 user 消息，
 * 规避不同供应商对 assistant.tool_calls 后继消息的协议约束，跨模型稳定。</p>
 */
@Slf4j
@Service
public class ChatMemoryService {

    private static final String SESSION_KEY_PREFIX = "agent:session:";
    private static final String OPS_KEY_PREFIX = "agent:session-ops:";
    private static final String PENDING_KEY_PREFIX = "agent:session-pending:";
    /** 单轮操作超限（模型一次发起多个动作被截断）的待注入提示 */
    private static final String OVERFLOW_KEY_PREFIX = "agent:session-overflow:";
    /**
     * 单用户会话索引：ZSET，member=sessionId、score=最后活跃毫秒时间戳。
     *
     * <p>两个用途：① 会话数上限的淘汰依据（取 score 最小的最久未活跃会话）；
     * ② 将来「最近会话指针」的数据源（score 最大的即最近使用的会话）。</p>
     */
    private static final String USER_SESSIONS_KEY_PREFIX = "agent:user-sessions:";

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final AgentProperties properties;

    private final TypeReference<Map<String, Object>> mapType = new TypeReference<>() {
    };
    private final TypeReference<List<AgentToolCall>> toolsType = new TypeReference<>() {
    };
    private final TypeReference<List<String>> stringListType = new TypeReference<>() {
    };

    public ChatMemoryService(StringRedisTemplate redis, ObjectMapper objectMapper, AgentProperties properties) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    /** 追加一条 user 消息并修剪 + 续期。 */
    public void pushUser(Long userId, String sessionId, String content) {
        push(userId, sessionId, "user", content);
    }

    /** 追加一条 assistant 消息并修剪 + 续期。 */
    public void pushAssistant(Long userId, String sessionId, String content) {
        push(userId, sessionId, "assistant", content);
    }

    private void push(Long userId, String sessionId, String role, String content) {
        if (sessionId == null || sessionId.isBlank() || content == null || content.isBlank()) {
            return;
        }
        String key = sessionKey(userId, sessionId);
        try {
            String json = objectMapper.writeValueAsString(new HistoryEntry(role, content, System.currentTimeMillis()));
            Long len = redis.opsForList().rightPush(key, json);
            int max = Math.max(1, properties.getMemory().getMaxMessages());
            if (len != null && len > max) {
                // 保留**最新**的 max 条：rightPush 追加在尾部，最新的是最后 max 个 → LTRIM -max -1。
                //
                // 这里原先是 trim(0, max - 1)，而 LTRIM 的区间是按下标算的、0 = 最早的一条，
                // 于是"超出上限"被丢掉的恰恰是**刚写入的新消息**（实测：RPUSH a b c d e 后
                // LTRIM 0 2 得到 [a,b,c]）。列表头部从此再也不变，会话记忆在第 max 条之后彻底冻结——
                // 用户与模型都停在最早那几轮里，最近说过什么完全看不到。
                redis.opsForList().trim(key, -max, -1);
                alignToRoundStart(key);
            }
            redis.expire(key, properties.getMemory().getTtlHours(), TimeUnit.HOURS);
            // 会话活跃即刷新索引（同时触发会话数上限的淘汰检查）
            touchSession(userId, sessionId);
        } catch (Exception e) {
            // 记忆写入失败不应阻断本轮回答（降级为无上下文会话），仅告警
            log.warn("写入 Agent 会话记忆失败 sessionId={}", sessionId, e);
        }
    }

    /**
     * 保证列表首条是 user 消息——即裁剪落在「轮」的起点上。
     *
     * <p><b>为什么需要</b>：一轮 = 一条 user + 它引出的 assistant 回复，正常写入是偶数条，
     * 按条数裁剪天然对齐。但只要出现一次落盘不对称（用户侧内容为空被空值守卫跳过，见
     * {@code AgentTurnService#persistTurn}），奇偶就<b>永久错位</b>，此后每次裁剪都切在轮中间；
     * 而模型看到"没有提问的回答"会给出莫名其妙的下文（工具轮尤其危险，见 {@code trimHistory}）。</p>
     *
     * <p>多裁一条的代价是少一轮上下文，方向安全。</p>
     */
    private void alignToRoundStart(String key) {
        String head = redis.opsForList().index(key, 0);
        if (head == null || isUserEntry(head)) {
            return;
        }
        redis.opsForList().trim(key, 1, -1);
    }

    /** 判断某条序列化记录是否为 user 消息；解析失败按 user 处理（宁可不动，也不误删上下文）。 */
    private boolean isUserEntry(String json) {
        try {
            return "user".equals(objectMapper.readValue(json, mapType).get("role"));
        } catch (Exception e) {
            return true;
        }
    }

    /** 按时间序返回该会话最近的记忆（≤maxMessages 条）；异常时返回空列表。 */
    public List<HistoryEntry> recent(Long userId, String sessionId) {
        List<HistoryEntry> result = new ArrayList<>();
        if (sessionId == null || sessionId.isBlank()) {
            return result;
        }
        try {
            List<String> jsons = redis.opsForList().range(sessionKey(userId, sessionId), 0, -1);
            if (jsons == null) {
                return result;
            }
            for (String json : jsons) {
                Map<String, Object> map = objectMapper.readValue(json, mapType);
                String role = String.valueOf(map.getOrDefault("role", "user"));
                String content = String.valueOf(map.getOrDefault("content", ""));
                Object tsObj = map.get("ts");
                long ts = tsObj instanceof Number n ? n.longValue() : 0L;
                result.add(new HistoryEntry(role, content, ts));
            }
        } catch (Exception e) {
            log.warn("读取 Agent 会话记忆失败 sessionId={}", sessionId, e);
        }
        return result;
    }

    /**
     * 该用户最近活跃的会话 ID（没有则 null）。
     *
     * <p><b>用途</b>：前端 localStorage 里没有 sessionId 时（换设备、清了浏览器数据、无痕窗口），
     * 据此接回上一次对话，而不是开一个空白会话——上下文本来还在 Redis 的 TTL 内，丢掉纯属浪费。</p>
     *
     * <p>数据源就是 {@link #touchSession} 维护的 ZSET：score 是最后活跃毫秒，取最大的那条即可，
     * 无需额外存储。按 userId 隔离，故只能取到自己的会话。</p>
     *
     * <p>「清空会话」会同时删除数据与索引条目，所以清空后不会自己复活。</p>
     *
     * @return 最近活跃的 sessionId；无会话或 Redis 异常时返回 null（调用方据此新建会话）
     */
    public String latestSession(Long userId) {
        try {
            Set<String> top = redis.opsForZSet().reverseRange(userSessionsKey(userId), 0, 0);
            if (CollectionUtils.isEmpty(top)) {
                return null;
            }
            return top.iterator().next();
        } catch (Exception e) {
            // 取不到就当没有：这是可用性增强，不该让它阻塞对话
            log.warn("读取最近会话失败 userId={}", userId, e);
            return null;
        }
    }

    /** 清空会话（前端「清空上下文」按钮），连同操作计数器与各类挂起态一并重置。 */
    public void clear(Long userId, String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        deleteSessionData(userId, sessionId);
        try {
            redis.opsForZSet().remove(userSessionsKey(userId), sessionId);
        } catch (Exception e) {
            log.warn("移除会话索引失败 sessionId={}", sessionId, e);
        }
    }

    /**
     * 追加本轮发起的「待确认操作意图」（挂起态）。页面刷新后据此恢复卡片、或注入 System 让模型
     * 能对“继续刚才的操作”精确复述，避免把待确认动作当普通闲聊历史丢失。带会话同款 TTL。
     *
     * <p><b>为什么是追加而不是覆盖</b>：模型可能在多轮里先后发起不同动作，而用户只确认了其中一部分。
     * 若每轮覆盖，未确认的旧意图会凭空消失——用户回头点那张仍在界面上的旧卡片时，
     * 服务端找不到对应的下发记录（见 {@link #consumeReceipts}），会把合法回执误判为伪造。
     * 追加的代价是条目会累积，故用 {@code max-pending-actions} 设上限、超出丢最旧。</p>
     */
    public void appendPending(Long userId, String sessionId, List<AgentToolCall> tools) {
        if (sessionId == null || sessionId.isBlank() || CollectionUtils.isEmpty(tools)) {
            return;
        }
        List<AgentToolCall> outstanding = new ArrayList<>(pending(userId, sessionId));
        outstanding.addAll(tools);
        int max = Math.max(1, properties.getMaxPendingActions());
        if (outstanding.size() > max) {
            outstanding = new ArrayList<>(outstanding.subList(outstanding.size() - max, outstanding.size()));
        }
        writePending(userId, sessionId, outstanding);
    }

    /**
     * 校验并消费一批操作执行回执：仅「命中服务端下发记录」的回执被采信。
     *
     * <p>匹配规则：{@code nonce} 非空且与挂起意图一致，且 {@code action} 与 {@code purpose} 也一致
     * （后两者用于发现"nonce 与内容对不上"的客户端错配）。命中即从挂起列表移除（<b>消费式</b>），
     * 因此同一份回执重放第二次必然匹配失败。</p>
     *
     * <p>挂起意图若没有 nonce（升级前落盘、无 nonce 的历史数据）一律判为未命中——<b>fail-closed</b>：
     * 宁可让一张老卡片确认失败并提示重发，也不退回"只比 action+purpose"的弱校验（那正是本次要堵的洞）。</p>
     *
     * @param receipts 前端上报的执行回执（不可信输入）
     * @return 采信/未采信的回执分组；调用方据此决定哪些算"已发生"、哪些要告知用户重发
     */
    public ConsumeResult consumeReceipts(Long userId, String sessionId, List<ToolExecutionResult> receipts) {
        if (CollectionUtils.isEmpty(receipts) || sessionId == null || sessionId.isBlank()) {
            return new ConsumeResult(List.of(), List.of());
        }
        List<AgentToolCall> outstanding = new ArrayList<>(pending(userId, sessionId));
        List<ToolExecutionResult> matched = new ArrayList<>();
        List<ToolExecutionResult> unmatched = new ArrayList<>();
        for (ToolExecutionResult receipt : receipts) {
            int hit = indexOfMatch(outstanding, receipt);
            if (hit >= 0) {
                outstanding.remove(hit);
                matched.add(receipt);
            } else {
                unmatched.add(receipt);
            }
        }
        if (!matched.isEmpty()) {
            writePending(userId, sessionId, outstanding);
        }
        if (!unmatched.isEmpty()) {
            log.warn("Agent 收到 {} 条未匹配到下发记录的操作回执 sessionId={}（已丢弃，不作为已执行）",
                    unmatched.size(), sessionId);
        }
        return new ConsumeResult(List.copyOf(matched), List.copyOf(unmatched));
    }

    /** 回执校验结果：{@code matched} 已采信并消费，{@code unmatched} 未采信（不得当作已发生）。 */
    public record ConsumeResult(List<ToolExecutionResult> matched, List<ToolExecutionResult> unmatched) {
    }

    /** 在挂起列表中查找与该回执对应的意图下标；无匹配返回 -1。 */
    private static int indexOfMatch(List<AgentToolCall> outstanding, ToolExecutionResult receipt) {
        for (int i = 0; i < outstanding.size(); i++) {
            AgentToolCall intent = outstanding.get(i);
            if (intent.nonce() == null || intent.nonce().isBlank()) {
                continue; // 无 nonce 的历史挂起意图不可被采信
            }
            if (Objects.equals(intent.nonce(), receipt.nonce())
                    && Objects.equals(intent.action(), receipt.action())
                    && Objects.equals(intent.purpose(), receipt.purpose())) {
                return i;
            }
        }
        return -1;
    }

    /** 覆盖写入挂起意图列表；空列表即删除该键。 */
    private void writePending(Long userId, String sessionId, List<AgentToolCall> tools) {
        try {
            String key = pendingKey(userId, sessionId);
            if (CollectionUtils.isEmpty(tools)) {
                redis.delete(key);
                return;
            }
            redis.opsForValue().set(key, objectMapper.writeValueAsString(tools),
                    properties.getMemory().getTtlHours(), TimeUnit.HOURS);
        } catch (Exception e) {
            log.warn("写入 Agent 待确认操作失败 sessionId={}", sessionId, e);
        }
    }

    /** 读取会话当前全部「待确认操作意图」（未命中消费的都在）；无/异常返回空列表。 */
    public List<AgentToolCall> pending(Long userId, String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return List.of();
        }
        try {
            String json = redis.opsForValue().get(pendingKey(userId, sessionId));
            if (json == null) {
                return List.of();
            }
            List<AgentToolCall> tools = objectMapper.readValue(json, toolsType);
            return tools == null ? List.of() : tools;
        } catch (Exception e) {
            log.warn("读取 Agent 待确认操作失败 sessionId={}", sessionId, e);
            return List.of();
        }
    }

    /**
     * 记录「本轮模型一次发起了多个操作、被服务端截断」的违规信息（存被丢弃动作的目的说明）。
     *
     * <p>本轮回复文本此时已生成完毕，无法即时纠正，只能带到下一轮随用户消息注入提示
     * （见 {@link #takeToolOverflow}）。带会话同款 TTL，用户中途离开不会留下永久状态。</p>
     *
     * @param discardedPurposes 被丢弃动作的目的说明（空则视为无违规，不写）
     */
    public void recordToolOverflow(Long userId, String sessionId, List<String> discardedPurposes) {
        if (sessionId == null || sessionId.isBlank() || CollectionUtils.isEmpty(discardedPurposes)) {
            return;
        }
        try {
            redis.opsForValue().set(overflowKey(userId, sessionId),
                    objectMapper.writeValueAsString(discardedPurposes),
                    properties.getMemory().getTtlHours(), TimeUnit.HOURS);
        } catch (Exception e) {
            log.warn("写入 Agent 单轮操作超限记录失败 sessionId={}", sessionId, e);
        }
    }

    /**
     * 取出并清除上一轮的「单轮操作超限」记录（<b>消费式</b>：读一次即删）。
     *
     * <p>消费式是为了让该提示只影响紧随其后的一轮——反复注入同一纠偏语会持续占据上下文，
     * 且在违规已纠正后变成噪声。</p>
     *
     * @return 被丢弃动作的目的说明；无记录/异常时返回空列表
     */
    public List<String> takeToolOverflow(Long userId, String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return List.of();
        }
        try {
            String key = overflowKey(userId, sessionId);
            String json = redis.opsForValue().get(key);
            if (json == null) {
                return List.of();
            }
            redis.delete(key);
            List<String> purposes = objectMapper.readValue(json, stringListType);
            return purposes == null ? List.of() : purposes;
        } catch (Exception e) {
            log.warn("读取 Agent 单轮操作超限记录失败 sessionId={}", sessionId, e);
            return List.of();
        }
    }

    /** 会话内已执行的页面操作次数。 */
    public long toolOps(Long userId, String sessionId) {
        String v = redis.opsForValue().get(opsKey(userId, sessionId));
        return v == null ? 0 : parseLong(v);
    }

    /** 记录一次“确认后执行”的页面操作并返回累计次数（带 TTL 续期）。 */
    public long incrementToolOps(Long userId, String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return 0;
        }
        try {
            Long count = redis.opsForValue().increment(opsKey(userId, sessionId));
            redis.expire(opsKey(userId, sessionId), properties.getMemory().getTtlHours(), TimeUnit.HOURS);
            return count == null ? 0 : count;
        } catch (Exception e) {
            log.warn("记录 Agent 会话操作计数失败 sessionId={}", sessionId, e);
            return 0;
        }
    }

    private long parseLong(String v) {
        try {
            return Long.parseLong(v);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * 刷新会话活跃时间（ZSET score = 当前毫秒），并在超过 {@code max-sessions-per-user} 时，
     * 淘汰最久未活跃的会话及其全部数据键。
     *
     * <p>为什么需要上限：sessionId 由前端自由提供，没有上限时一个用户可以用不同随机 sessionId
     * 无限创建 Redis Key（每个最多 20 条消息 + 24h TTL），形成无界存储放大。</p>
     *
     * <p>整体 try-catch：这只是防存储放大的兜底，失败绝不能影响正常对话。</p>
     */
    private void touchSession(Long userId, String sessionId) {
        try {
            String key = userSessionsKey(userId);
            redis.opsForZSet().add(key, sessionId, System.currentTimeMillis());
            redis.expire(key, properties.getMemory().getTtlHours(), TimeUnit.HOURS);

            int max = Math.max(1, properties.getMaxSessionsPerUser());
            Long size = redis.opsForZSet().zCard(key);
            if (size == null || size <= max) {
                return;
            }
            // score 小的即最久未活跃：取 [0, size-max-1] 区间淘汰，直到回落至上限
            Set<String> stale = redis.opsForZSet().range(key, 0, size - max - 1);
            if (CollectionUtils.isEmpty(stale)) {
                return;
            }
            redis.opsForZSet().remove(key, stale.toArray());
            for (String staleSession : stale) {
                deleteSessionData(userId, staleSession);
            }
            log.info("会话数超过上限 {}，已淘汰最久未活跃的 {} 个会话 userId={}", max, stale.size(), userId);
        } catch (Exception e) {
            log.warn("维护会话索引失败 userId={}", userId, e);
        }
    }

    /** 删除某会话的全部数据键（不含会话索引本身，索引由调用方按语义处理）。 */
    private void deleteSessionData(Long userId, String sessionId) {
        redis.delete(sessionKey(userId, sessionId));
        redis.delete(opsKey(userId, sessionId));
        redis.delete(pendingKey(userId, sessionId));
        redis.delete(overflowKey(userId, sessionId));
    }

    /** 会话记忆 Key：绑定 userId，防止跨用户枚举 sessionId。 */
    private String sessionKey(Long userId, String sessionId) {
        return SESSION_KEY_PREFIX + uidPart(userId) + ":" + sessionId;
    }

    private String opsKey(Long userId, String sessionId) {
        return OPS_KEY_PREFIX + uidPart(userId) + ":" + sessionId;
    }

    private String pendingKey(Long userId, String sessionId) {
        return PENDING_KEY_PREFIX + uidPart(userId) + ":" + sessionId;
    }

    private String overflowKey(Long userId, String sessionId) {
        return OVERFLOW_KEY_PREFIX + uidPart(userId) + ":" + sessionId;
    }

    /** 单用户会话索引 Key（ZSET）。注意这里按 userId 隔离，不含 sessionId。 */
    private String userSessionsKey(Long userId) {
        return USER_SESSIONS_KEY_PREFIX + uidPart(userId);
    }

    /**
     * 隔离键中的用户部分。
     *
     * <p>身份缺失时 <b>fail-closed</b>：抛异常让调用方拿不到会话，而不是回落到某个共享命名空间
     * （回落会让不同访客在 sessionId 相同时互相读到对方上下文）。调用方多为「读失败即降级」的语义，
     * 抛异常的结果是"本轮无会话上下文"，方向安全。</p>
     */
    private String uidPart(Long userId) {
        if (userId == null) {
            throw new BizException(ResultCode.UNAUTHORIZED.getCode(), "缺少用户身份，无法访问会话");
        }
        return String.valueOf(userId);
    }
}
