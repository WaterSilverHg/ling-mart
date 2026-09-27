package com.lingmart.agent.service;

import com.lingmart.agent.config.AgentProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.concurrent.Executors;

/**
 * 知识库启动初始化：当 {@code lingmart.agent.kb.rebuild-on-start=true} 且知识库尚未就绪时，
 * 在后台线程静默重建一次（向量库未起/嵌入失败仅告警，绝不阻断服务启动）。
 */
@Slf4j
@Component
public class KnowledgeBaseInitializer implements ApplicationRunner {

    private final KnowledgeBaseService knowledgeBaseService;
    private final AgentProperties properties;

    public KnowledgeBaseInitializer(KnowledgeBaseService knowledgeBaseService, AgentProperties properties) {
        this.knowledgeBaseService = knowledgeBaseService;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!properties.getKb().isRebuildOnStart()) {
            return;
        }
        if (knowledgeBaseService.isReady()) {
            return;
        }
        Executors.newVirtualThreadPerTaskExecutor().execute(() -> {
            try {
                // 稍作延迟，等待 ChromaDB 就绪
                Thread.sleep(3000);
                long count = knowledgeBaseService.rebuild();
                log.info("Agent 启动后台重建知识库完成，共 {} 分块", count);
            } catch (Exception e) {
                log.warn("Agent 启动后台重建知识库失败（可稍后调用重建接口）：{}", e.getMessage());
            }
        });
    }
}
