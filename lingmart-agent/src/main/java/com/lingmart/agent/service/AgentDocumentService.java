package com.lingmart.agent.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lingmart.agent.config.AgentProperties;
import com.lingmart.agent.dto.DocumentInfo;
import com.lingmart.common.exception.BizException;
import com.lingmart.common.result.ResultCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 会话文档存储：每个会话保留<b>一份</b>用户上传的材料（开店清单等），发送时注入当轮 prompt。
 *
 * <p><b>为什么是"每会话一份"而不是按用户建一套文档库</b>：一份的存储上界就是文本上限，
 * 而生命周期可以完全复用会话——会话数上限淘汰、用户点「清空会话」都会走
 * {@code ChatMemoryService#deleteSessionData}，把它一并删掉即可。
 * 独立文档库则要另建一套按用户的淘汰逻辑（否则用户反复上传就能把 Redis 撑大），
 * 而当前 UI 本来就只有一个附件位，没有多文件并存的场景。</p>
 *
 * <p>key 绑定 userId（{@code agent:session-doc:{userId}:{sessionId}}），
 * 杜绝跨用户用 sessionId 枚举窥探他人上传的材料。</p>
 */
@Slf4j
@Service
public class AgentDocumentService {

    private static final String DOC_KEY_PREFIX = "agent:session-doc:";

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final AgentProperties properties;

    private final TypeReference<StoredDocument> storedType = new TypeReference<>() {
    };

    public AgentDocumentService(StringRedisTemplate redis, ObjectMapper objectMapper, AgentProperties properties) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    /**
     * 保存本会话的文档（覆盖旧的那份），返回给前端的元信息。
     *
     * <p>写入失败<b>直接抛</b>而不是像会话记忆那样降级：这里若静默失败，用户会看到附件条、
     * 以为传成功了，发送时却发现模型完全不知道有这份材料——不如当场告诉他重试。</p>
     */
    public DocumentInfo store(Long userId, String sessionId, String name, String text,
                              String summary, boolean truncated) {
        String documentId = UUID.randomUUID().toString().replace("-", "");
        StoredDocument doc = new StoredDocument(documentId, name, summary, text, truncated);
        try {
            redis.opsForValue().set(docKey(userId, sessionId), objectMapper.writeValueAsString(doc),
                    properties.getMemory().getTtlHours(), TimeUnit.HOURS);
        } catch (Exception e) {
            log.warn("保存 Agent 会话文档失败 sessionId={}", sessionId, e);
            throw new BizException("文档保存失败，请稍后再试");
        }
        return new DocumentInfo(documentId, name, summary, text.length(), truncated);
    }

    /**
     * 读取本会话的文档。
     *
     * <p>{@code documentId} 对不上就返回 {@code null}：前端回传的是它收到的那一份，
     * 若期间用户又传了别的文件（覆盖了存储），旧卡片上的 id 就不该再捞出新的正文来。
     * <b>读后不删</b>——发送失败时前端还留着附件条，用户可以原样重发，不必重新上传；
     * 存储上界由"每会话一份 + TTL"保证。</p>
     *
     * @return 命中的文档；无文档、id 不匹配或 Redis 异常均为 {@code null}
     */
    public StoredDocument load(Long userId, String sessionId, String documentId) {
        if (sessionId == null || sessionId.isBlank() || documentId == null || documentId.isBlank()) {
            return null;
        }
        try {
            String json = redis.opsForValue().get(docKey(userId, sessionId));
            if (json == null) {
                return null;
            }
            StoredDocument doc = objectMapper.readValue(json, storedType);
            if (doc == null || !Objects.equals(doc.documentId(), documentId)) {
                return null;
            }
            return doc;
        } catch (Exception e) {
            log.warn("读取 Agent 会话文档失败 sessionId={}", sessionId, e);
            return null;
        }
    }

    /** 删除本会话文档（清空会话、会话淘汰时调用）。失败只告警：它总会随 TTL 过期。 */
    public void delete(Long userId, String sessionId) {
        if (sessionId == null || sessionId.isBlank() || userId == null) {
            return;
        }
        try {
            redis.delete(docKey(userId, sessionId));
        } catch (Exception e) {
            log.warn("删除 Agent 会话文档失败 sessionId={}", sessionId, e);
        }
    }

    /** 会话文档 Key：绑定 userId，防止跨用户枚举 sessionId。 */
    private String docKey(Long userId, String sessionId) {
        if (userId == null) {
            // 与 ChatMemoryService#uidPart 同口径：身份缺失时 fail-closed，
            // 不回落到某个共享命名空间（回落会让不同访客在 sessionId 相同时互相读到对方上传的材料）
            throw new BizException(ResultCode.UNAUTHORIZED.getCode(), "缺少用户身份，无法访问文档");
        }
        return DOC_KEY_PREFIX + userId + ":" + sessionId;
    }

    /**
     * 存储形态。
     *
     * @param documentId 随机 id，用于确认前端回传的确实是当前这一份
     * @param name       已净化的文件名（展示 + 注入时标注）
     * @param summary    解析摘要（如「12 行 × 4 列」）
     * @param text       抽取后的正文（已按上限截断）
     * @param truncated  正文是否被截断
     */
    public record StoredDocument(String documentId, String name, String summary,
                                 String text, boolean truncated) {
    }
}
