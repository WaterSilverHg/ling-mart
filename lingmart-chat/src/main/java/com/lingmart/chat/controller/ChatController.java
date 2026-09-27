package com.lingmart.chat.controller;

import com.lingmart.chat.config.StompPrincipal;
import com.lingmart.chat.dto.OpenConversationDTO;
import com.lingmart.chat.dto.SendMessageDTO;
import com.lingmart.chat.exception.ChatSendException;
import com.lingmart.chat.service.ChatService;
import com.lingmart.chat.vo.ConversationDetailVO;
import com.lingmart.chat.vo.ConversationVO;
import com.lingmart.chat.vo.MessageVO;
import com.lingmart.chat.vo.SendErrorVO;
import com.lingmart.common.constant.AuthConstants;
import com.lingmart.common.exception.BizException;
import com.lingmart.common.result.PageResult;
import com.lingmart.common.result.R;
import com.lingmart.common.result.ResultCode;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.handler.annotation.MessageExceptionHandler;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.util.List;

/**
 * 聊天接口：REST（会话/历史记录）+ WebSocket(STOMP) 实时消息。
 *
 * <p>REST 走网关 JWT 鉴权（Authorization 头）；STOMP 发送经 {@code /app/chat/send}，
 * 发送者身份由 {@code JwtChannelInterceptor} 注入的 principal 识别，持久化后广播到会话 topic。</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
public class ChatController {

    private final ChatService chatService;
    private final SimpMessagingTemplate messagingTemplate;

    /**
     * 发起/进入会话（返回会话详情与对方信息，手机号在会话窗口内展示）。
     */
    @PostMapping("/conversation")
    public R<ConversationDetailVO> openConversation(@Valid @RequestBody OpenConversationDTO dto,
                                                    @RequestHeader(value = AuthConstants.HEADER_USER_ID, required = false) Long userId) {
        return R.ok(chatService.openConversation(userId, dto.getShopId()));
    }

    /**
     * 我的会话列表（买家侧 + 老板侧）。
     */
    @GetMapping("/conversations")
    public R<List<ConversationVO>> conversations(
            @RequestHeader(value = AuthConstants.HEADER_USER_ID, required = false) Long userId) {
        return R.ok(chatService.listConversations(userId));
    }

    /**
     * 会话消息历史（分页，升序）。
     */
    @GetMapping("/conversations/{id}/messages")
    public R<PageResult<MessageVO>> messages(@PathVariable Long id,
                                             @RequestParam(required = false, defaultValue = "1") long page,
                                             @RequestParam(required = false, defaultValue = "20") long size,
                                             @RequestHeader(value = AuthConstants.HEADER_USER_ID, required = false) Long userId) {
        return R.ok(chatService.messages(userId, id, page, size));
    }

    /**
     * 会话消息增量补拉（断线重连用）：返回 id 大于 sinceId 的消息（升序，最多 limit 条）。
     *
     * <p>WebSocket 重连成功后，前端以本地最后一条消息 id 为 sinceId 调用本接口，
     * 补齐断线期间漏掉的实时消息（消息已在库中，不会丢）。</p>
     */
    @GetMapping("/conversations/{id}/messages/since")
    public R<List<MessageVO>> messagesSince(@PathVariable Long id,
                                            @RequestParam(required = false) Long sinceId,
                                            @RequestParam(required = false, defaultValue = "100") int limit,
                                            @RequestHeader(value = AuthConstants.HEADER_USER_ID, required = false) Long userId) {
        return R.ok(chatService.messagesSince(userId, id, sinceId, limit));
    }

    /**
     * STOMP 消息发送：客户端发到 /app/chat/send。
     *
     * <p>持久化成功后广播到 {@code /topic/conversation/{id}}，订阅该 topic 的会话双方实时收到。</p>
     */
    @MessageMapping("/chat/send")
    public void send(@Valid @Payload SendMessageDTO dto, Principal principal) {
        Long userId = extractUserId(principal);
        MessageVO vo;
        try {
            vo = chatService.sendMessage(userId, dto);
        } catch (Exception e) {
            // 不向上抛出：否则 Spring 回 STOMP ERROR 帧 → 连接被关闭 + 前端重连空窗，
            // 且前端无法知道是哪条消息失败。统一包装后由 onSendFailed 定向回执。
            Integer code = e instanceof BizException be ? be.getCode() : ResultCode.BIZ_ERROR.getCode();
            String reason = e.getMessage() == null ? "消息发送失败，请重试" : e.getMessage();
            throw new ChatSendException(dto, code, reason);
        }
        // 持久化成功后才广播：订阅会话 topic 的双方实时收到（发送方也靠这条回显确认）
        messagingTemplate.convertAndSend("/topic/conversation/" + dto.getConversationId(), vo);
    }

    /**
     * 发送失败回执：投递到发送者的专属队列 {@code /user/queue/errors}（前端订阅该目的地）。
     *
     * <p>携带 clientMsgId 与原文，前端可把对应气泡标记为「发送失败」并一键重发，
     * 避免旧实现「文本已被清空、界面无任何提示」。</p>
     */
    @MessageExceptionHandler(ChatSendException.class)
    public void onSendFailed(ChatSendException ex, Principal principal) {
        if (principal == null) {
            return;
        }
        SendMessageDTO dto = ex.getDto();
        SendErrorVO vo = new SendErrorVO();
        vo.setConversationId(dto == null ? null : dto.getConversationId());
        vo.setClientMsgId(dto == null ? null : dto.getClientMsgId());
        vo.setContent(dto == null ? null : dto.getContent());
        vo.setCode(ex.getCode());
        vo.setMsg(ex.getMessage());
        messagingTemplate.convertAndSendToUser(principal.getName(), "/queue/errors", vo);
        log.warn("聊天消息发送失败，已回执发送者：userId={}, conversationId={}, reason={}",
                principal.getName(), vo.getConversationId(), ex.getMessage());
    }

    /**
     * 兜底：参数校验失败等未预期异常同样回执（无 clientMsgId，前端标记最后一条「发送中」气泡），
     * 保证任何发送失败都不会表现为「消息凭空消失 + 连接断开」。
     */
    @MessageExceptionHandler(Exception.class)
    public void onSendUnexpected(Exception ex, Principal principal) {
        if (principal == null) {
            return;
        }
        SendErrorVO vo = new SendErrorVO();
        vo.setCode(ResultCode.BIZ_ERROR.getCode());
        vo.setMsg("消息发送失败，请重试");
        messagingTemplate.convertAndSendToUser(principal.getName(), "/queue/errors", vo);
        log.warn("聊天消息处理异常，已回执发送者：userId={}", principal.getName(), ex);
    }

    /** 从 STOMP principal 提取用户 ID（由 JwtChannelInterceptor 注入） */
    private Long extractUserId(Principal principal) {
        if (principal instanceof StompPrincipal p) {
            return p.getUserId();
        }
        throw new BizException(ResultCode.UNAUTHORIZED.getCode(), "WebSocket 未认证");
    }
}
