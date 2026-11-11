package com.lingmart.agent.controller;

import com.lingmart.agent.config.AgentProperties;
import com.lingmart.agent.dto.AgentCaller;
import com.lingmart.agent.dto.DocumentInfo;
import com.lingmart.agent.service.AgentDocumentService;
import com.lingmart.agent.service.DocumentTextExtractor;
import com.lingmart.agent.service.JwtAuthService;
import com.lingmart.common.exception.BizException;
import com.lingmart.common.result.R;
import com.lingmart.common.util.RedisRateLimiter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

/**
 * Agent 文档上传（开店材料）。
 *
 * <p>浮窗里选文件后走这里：服务端解码成文本并存进本会话，返回元信息给前端渲染附件条。
 * <b>正文不回传前端</b>——早先的实现是把解析结果塞回输入框再随消息发出，
 * 那既淹没了输入框，又会撞上 {@code max-message-length}（默认 2000 字）而直接发不出去。</p>
 *
 * <p>用户身份从 {@code Authorization} 头<b>自行验签 JWT</b> 得到（与对话接口同口径，
 * 见 {@link JwtAuthService}），不信任网关透传的 {@code X-User-Id}。</p>
 *
 * <p>体积有两道闸：更外层的 Spring multipart 限制（超限由 GlobalExceptionHandler 兜成友好提示），
 * 以及这里的业务校验（给出带具体数值的提示）。</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/agent/doc")
public class AgentDocumentController {

    /** 文件名进模型上下文前的长度上限 */
    private static final int MAX_NAME_CHARS = 80;

    private final AgentDocumentService documentService;
    private final DocumentTextExtractor extractor;
    private final JwtAuthService jwtAuthService;
    private final AgentProperties properties;
    private final RedisRateLimiter rateLimiter;

    public AgentDocumentController(AgentDocumentService documentService, DocumentTextExtractor extractor,
                                   JwtAuthService jwtAuthService, AgentProperties properties,
                                   RedisRateLimiter rateLimiter) {
        this.documentService = documentService;
        this.extractor = extractor;
        this.jwtAuthService = jwtAuthService;
        this.properties = properties;
        this.rateLimiter = rateLimiter;
    }

    /**
     * 上传文档并解析。
     *
     * @param file          multipart 字段名 file
     * @param sessionId     会话标识：文档存在这个会话下，发送时凭 documentId 取回
     * @param authorization 原始 Authorization 头（自行验签）
     * @return 文档元信息（documentId / 文件名 / 摘要 / 字符数 / 是否截断）
     */
    @PostMapping
    public R<DocumentInfo> upload(@RequestParam("file") MultipartFile file,
                                  @RequestParam("sessionId") String sessionId,
                                  @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false)
                                  String authorization) {
        AgentProperties.Document cfg = properties.getDocument();
        if (!cfg.isEnabled()) {
            throw new BizException("文档上传功能已关闭");
        }
        AgentCaller caller = jwtAuthService.resolve(authorization);
        Long userId = caller.userId();
        if (file == null || file.isEmpty()) {
            throw new BizException("请选择要上传的文件");
        }
        if (file.getSize() > cfg.getMaxFileSize().toBytes()) {
            throw new BizException("文件不能超过 " + cfg.getMaxFileSize().toMegabytes() + "MB");
        }
        if (!StringUtils.hasText(sessionId)) {
            throw new BizException("缺少会话标识，请刷新页面后重试");
        }
        // 频控：解析动作有 CPU 成本（xlsx/docx 要解包解析），不做限制可被反复上传打满
        if (!rateLimiter.allow("agent-doc:u" + userId, cfg.getRatePerMinute(), 60_000L)) {
            throw new BizException("上传过于频繁，请稍后再试");
        }

        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            log.warn("读取上传文件失败 userId={}：{}", userId, e.getMessage());
            throw new BizException("文件读取失败，请重试");
        }

        // 类型白名单与截断都在抽取器里（单一事实来源），不支持的扩展名由它抛 BizException
        DocumentTextExtractor.Extracted extracted = extractor.extract(file.getOriginalFilename(), bytes);
        if (!StringUtils.hasText(extracted.text())) {
            throw new BizException("这个文件里没有读到内容，请确认它包含文字或表格");
        }

        DocumentInfo info = documentService.store(userId, sessionId, safeName(file.getOriginalFilename()),
                extracted.text(), extracted.summary(), extracted.truncated());
        log.info("Agent 文档上传成功 userId={} name={} chars={} truncated={}",
                userId, info.name(), info.chars(), info.truncated());
        return R.ok(info);
    }

    /**
     * 文件名要随文档一起进模型上下文，必须先净化。
     *
     * <p>它是用户可控的字符串：只取基名（去掉 {@code ../} 之类的路径）、压掉控制字符与换行
     * （换行会把提示词的段落结构提前切断），再截断到定长。</p>
     */
    private static String safeName(String original) {
        if (!StringUtils.hasText(original)) {
            return "未命名文件";
        }
        String base = original.replace('\\', '/');
        int slash = base.lastIndexOf('/');
        if (slash >= 0) {
            base = base.substring(slash + 1);
        }
        String cleaned = base.replaceAll("[\\p{Cntrl}\\t\\r\\n]+", " ").trim();
        if (cleaned.isEmpty()) {
            return "未命名文件";
        }
        return cleaned.length() <= MAX_NAME_CHARS ? cleaned : cleaned.substring(0, MAX_NAME_CHARS);
    }
}
