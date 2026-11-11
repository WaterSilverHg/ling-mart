package com.lingmart.agent.controller;

import com.lingmart.agent.service.JwtAuthService;
import com.lingmart.agent.service.KbEvalService;
import com.lingmart.agent.service.KnowledgeBaseService;
import com.lingmart.common.result.R;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 知识库管理接口（仅 ROLE_ADMIN）。用于向量库首次就绪后手动重建，或增量更新知识种子。
 *
 * <p>这些路径虽含 /admin 但**不在网关 {@code /api/admin/**} 段**（该段由网关强制角色校验），
 * 因此必须在本控制器内自己把关。把关方式是 <b>验签 JWT 并取其中的角色声明</b>
 * （{@link JwtAuthService#resolveAdmin}），而<b>不是</b>读 {@code X-User-Role} 请求头——
 * 后者是客户端可自带的，只要端口可达就能伪造管理员。</p>
 */
@Slf4j
@RestController
public class AgentKbAdminController {

    private final KnowledgeBaseService knowledgeBaseService;
    private final KbEvalService kbEvalService;
    private final JwtAuthService jwtAuthService;

    public AgentKbAdminController(KnowledgeBaseService knowledgeBaseService, KbEvalService kbEvalService,
                                  JwtAuthService jwtAuthService) {
        this.knowledgeBaseService = knowledgeBaseService;
        this.kbEvalService = kbEvalService;
        this.jwtAuthService = jwtAuthService;
    }

    /**
     * 重建知识库（首次部署后必做一次，否则知识检索始终为空且不报错）。
     *
     * <p>内置 kb 资源 + 可配额外目录 → 切块 → 分批幂等 Upsert → 清理陈旧分块。</p>
     */
    @PostMapping("/api/agent/admin/kb/rebuild")
    public R<Long> rebuild(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        jwtAuthService.resolveAdmin(authorization);
        long count = knowledgeBaseService.rebuild();
        log.info("管理员触发知识库重建完成，共 {} 分块", count);
        return R.ok(count);
    }

    /** 知识库状态（重建后应看到 `ready:true`，否则检索始终为空且不报错）。 */
    @GetMapping("/api/agent/admin/kb/status")
    public R<Map<String, Object>> status(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        jwtAuthService.resolveAdmin(authorization);
        return R.ok(Map.of(
                "available", knowledgeBaseService.available(),
                "ready", knowledgeBaseService.isReady(),
                "chunkCount", knowledgeBaseService.chunkCount()));
    }

    /**
     * 召回评估：用内置评估集（{@code kb-eval/golden-set.json}）跑一遍真实检索链路，返回 recall@K。
     *
     * <p>只走检索层、不调用 LLM，因此结果可重复且不消耗对话额度；用于回答
     * "调完 Top-K / 阈值 / 改写之后，召回到底有没有变好"。评估前需知识库已就绪
     * （未重建时会全部落空、`recall` 为 0，先看 `kb/status` 的 `ready`）。</p>
     *
     * @param topK 可选，覆盖本次评估使用的检索段落数；不传则用配置值
     */
    @GetMapping("/api/agent/admin/kb/eval")
    public R<KbEvalService.EvalReport> eval(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestParam(required = false) Integer topK) {
        jwtAuthService.resolveAdmin(authorization);
        KbEvalService.EvalReport report = kbEvalService.evaluate(topK);
        log.info("管理员触发知识库召回评估：topK={} total={} hits={} recall={}",
                report.topK(), report.total(), report.hits(), report.recall());
        return R.ok(report);
    }
}
