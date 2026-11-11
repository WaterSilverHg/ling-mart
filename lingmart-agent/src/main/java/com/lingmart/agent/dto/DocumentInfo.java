package com.lingmart.agent.dto;

/**
 * 上传文档的元信息（上传接口的响应，也是前端附件条要展示的内容）。
 *
 * <p><b>不含正文</b>：正文留在服务端，只在用户真正发送消息时注入当轮 prompt（见
 * {@code AgentPrompts#buildDocumentNote}）。前端从头到尾拿不到正文，也不需要——
 * 早先的实现是把解析结果塞回输入框再随消息发出，那既淹没了输入框，又会撞上
 * {@code max-message-length}（默认 2000 字）而直接发不出去。</p>
 *
 * @param documentId 文档 ID（前端发送时原样回传，服务端据此确认"确实是我刚上传的那份"）
 * @param name       原始文件名（仅用于展示与注入时的标注）
 * @param summary    解析结果摘要，如「12 行 × 4 列」「36 行文字」，供附件条直接显示
 * @param chars      抽取出的正文字符数（截断后的实际长度）
 * @param truncated  正文是否因超过上限被截断
 */
public record DocumentInfo(
        String documentId,
        String name,
        String summary,
        int chars,
        boolean truncated) {
}
