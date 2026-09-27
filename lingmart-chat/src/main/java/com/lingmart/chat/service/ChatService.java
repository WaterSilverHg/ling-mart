package com.lingmart.chat.service;

import com.lingmart.chat.dto.SendMessageDTO;
import com.lingmart.chat.vo.ConversationDetailVO;
import com.lingmart.chat.vo.ConversationVO;
import com.lingmart.chat.vo.MessageVO;
import com.lingmart.common.result.PageResult;

import java.util.List;

/**
 * 聊天服务接口。
 */
public interface ChatService {

    /**
     * 发起/进入会话：校验店铺营业，获取或创建「买家 × 店铺」会话，返回会话详情（含对方信息）。
     *
     * @param userId 当前用户 ID
     * @param shopId 店铺 ID
     * @return 会话详情
     */
    ConversationDetailVO openConversation(Long userId, Long shopId);

    /**
     * 我的会话列表（买家侧 + 老板侧），按最后消息时间倒序。
     *
     * @param userId 当前用户 ID
     * @return 会话列表
     */
    List<ConversationVO> listConversations(Long userId);

    /**
     * 会话消息历史（分页，升序返回）。
     *
     * @param userId         当前用户 ID（须为会话参与者）
     * @param conversationId 会话 ID
     * @param page           页码（从 1 起）
     * @param size           每页大小
     * @return 分页消息
     */
    PageResult<MessageVO> messages(Long userId, Long conversationId, long page, long size);

    /**
     * 会话消息增量补拉（断线重连用）：返回 id 大于 sinceId 的消息，升序，最多 limit 条。
     *
     * <p>典型用法：WebSocket 重连成功后，前端用本地最后一条消息 id 作为 sinceId 拉取断线
     * 期间漏掉的消息，避免「停留在聊天页时断线导致消息不显示」。sinceId 为空或 ≤0 时
     * 返回最新 limit 条（首次进入会话/本地无历史）。</p>
     *
     * @param userId         当前用户 ID（须为会话参与者）
     * @param conversationId 会话 ID
     * @param sinceId        本地已有最新消息 id（可空）
     * @param limit          单次上限（内部归一化到 1~200）
     * @return 升序消息列表（可能为空）
     */
    List<MessageVO> messagesSince(Long userId, Long conversationId, Long sinceId, int limit);

    /**
     * 发送消息：持久化 + 更新会话摘要，返回消息视图（由 Web 层推送给会话双方）。
     *
     * @param userId 发送者用户 ID（须为会话参与者）
     * @param dto    消息内容
     * @return 消息视图
     */
    MessageVO sendMessage(Long userId, SendMessageDTO dto);
}
