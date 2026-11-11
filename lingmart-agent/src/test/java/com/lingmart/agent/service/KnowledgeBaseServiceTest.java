package com.lingmart.agent.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 来源文件名解析：评估与埋点都依赖"从 Resource 描述里取出 basename"这一步。
 *
 * <p>入库时 {@code source} 存的是 {@code Resource#getDescription()} 的原文，
 * 不同加载方式（classpath / file / jar 内）格式并不相同，必须都能解析，否则召回率会被算成 0。</p>
 */
class KnowledgeBaseServiceTest {

    @Test
    void classpath描述应取出文件名() {
        assertEquals("01-platform-guide.md",
                KnowledgeBaseService.sourceName("class path resource [kb/01-platform-guide.md]"));
    }

    @Test
    void 文件系统描述应取出文件名() {
        assertEquals("05-refund-guide.md",
                KnowledgeBaseService.sourceName("file [/opt/lingmart/kb/05-refund-guide.md]"));
    }

    @Test
    void URL描述应取出文件名() {
        assertEquals("07-search-and-browse.md",
                KnowledgeBaseService.sourceName("URL [file:/app/target/classes/kb/07-search-and-browse.md]"));
    }

    @Test
    void jar内URL也应取出文件名() {
        assertEquals("09-seller-center.md",
                KnowledgeBaseService.sourceName(
                        "URL [jar:file:/app/app.jar!/BOOT-INF/classes!/kb/09-seller-center.md]"));
    }

    @Test
    void Windows路径反斜杠应正确分隔() {
        assertEquals("x.md", KnowledgeBaseService.sourceName("file [C:\\kb\\x.md]"));
    }

    @Test
    void 空值与空串返回空串() {
        assertEquals("", KnowledgeBaseService.sourceName(null));
        assertEquals("", KnowledgeBaseService.sourceName(""));
        assertEquals("", KnowledgeBaseService.sourceName("   "));
    }
}
