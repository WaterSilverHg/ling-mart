package com.lingmart.chat.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.lingmart.chat.entity.ChatConversation;
import com.lingmart.chat.entity.ChatMessage;
import com.lingmart.chat.dto.SendMessageDTO;
import com.lingmart.chat.feign.AuthFeignClient;
import com.lingmart.chat.feign.ShopFeignClient;
import com.lingmart.chat.mapper.ChatConversationMapper;
import com.lingmart.chat.mapper.ChatMessageMapper;
import com.lingmart.chat.service.ChatService;
import com.lingmart.chat.vo.ConversationDetailVO;
import com.lingmart.chat.vo.ConversationVO;
import com.lingmart.chat.vo.MessageVO;
import com.lingmart.chat.vo.PeerInfo;
import com.lingmart.common.dto.ShopInfo;
import com.lingmart.common.dto.UserInfo;
import com.lingmart.common.exception.BizException;
import com.lingmart.common.result.PageResult;
import com.lingmart.common.result.R;
import com.lingmart.common.result.ResultCode;
import com.lingmart.common.util.RedisRateLimiter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 聊天服务实现：会话管理 + 消息持久化 + 摘要更新。
 *
 * <p>实时推送由 Web 层在消息发送后 convertAndSend 到会话 topic，
 * 本实现仅负责业务校验与持久化，保持纯业务。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatServiceImpl implements ChatService {

    /** 店铺营业状态 */
    private static final int SHOP_ENABLED = 1;

    /** 增量补拉单次上限（防止超长会话一次拉爆前端；前端按此值判断是否需要降级全量重载） */
    private static final int SINCE_MAX_LIMIT = 200;

    private final ChatConversationMapper conversationMapper;
    private final ChatMessageMapper messageMapper;
    private final AuthFeignClient authFeignClient;
    private final ShopFeignClient shopFeignClient;

    /** 发送频控：每用户 30 秒内最多发消息条数（可配置，防刷屏） */
    @Value("${lingmart.chat.rate-limit.max-per-30s:30}")
    private int sendMaxPer30s;
    /** 分布式滑动窗口限流器（Redis，多实例共享同一窗口） */
    private final RedisRateLimiter sendLimiter;

    @Override
    public ConversationDetailVO openConversation(Long userId, Long shopId) {
        requireUserId(userId);
        R<ShopInfo> shopResult = shopFeignClient.getShopById(shopId);
        ShopInfo shop = shopResult == null ? null : shopResult.getData();
        if (shop == null || !Objects.equals(shop.getStatus(), SHOP_ENABLED)) {
            throw new BizException("店铺不存在或未营业");
        }
        if (Objects.equals(shop.getUserId(), userId)) {
            throw new BizException("不能和自己的店铺聊天");
        }

        // 一个买家对一个店铺只有一条会话，复用
        ChatConversation conv = conversationMapper.selectOne(
                Wrappers.<ChatConversation>lambdaQuery()
                        .eq(ChatConversation::getBuyerId, userId)
                        .eq(ChatConversation::getShopId, shopId));
        if (conv == null) {
            ChatConversation created = new ChatConversation();
            created.setBuyerId(userId);
            created.setShopId(shopId);
            created.setShopUserId(shop.getUserId());
            try {
                conversationMapper.insert(created);
            } catch (DuplicateKeyException e) {
                // 并发/前端重试下双方几乎同时点「联系老板」：uk_buyer_shop 兜底，后到者捕获后复用既有会话，
                // 而不是把唯一键冲突冒成 500「系统繁忙」
                created = conversationMapper.selectOne(Wrappers.<ChatConversation>lambdaQuery()
                        .eq(ChatConversation::getBuyerId, userId)
                        .eq(ChatConversation::getShopId, shopId));
                if (created == null) {
                    throw new BizException("创建会话失败，请重试");
                }
            }
            conv = created;
            log.info("创建/复用会话：buyerId={}, shopId={}, shopUserId={}", userId, shopId, shop.getUserId());
        }

        ConversationDetailVO vo = new ConversationDetailVO();
        vo.setConversationId(conv.getId());
        vo.setShopId(shopId);
        vo.setShopName(shop.getShopName());
        vo.setShopLogo(shop.getShopLogo());
        vo.setMyId(userId);
        vo.setPeer(toPeer(shop.getUserId()));
        return vo;
    }

    @Override
    public List<ConversationVO> listConversations(Long userId) {
        requireUserId(userId);
        List<ChatConversation> convs = conversationMapper.selectList(
                Wrappers.<ChatConversation>lambdaQuery()
                        .and(w -> w.eq(ChatConversation::getBuyerId, userId)
                                .or().eq(ChatConversation::getShopUserId, userId))
                        .orderByDesc(ChatConversation::getLastTime));
        if (convs.isEmpty()) {
            return List.of();
        }
        // 店铺与对端用户各一次批量 Feign（原来每条会话各打 1~2 次，属 N+1）：去重后取，按 id 建 Map
        Map<Long, ShopInfo> shopById = indexShops(fetchShops(
                convs.stream().map(ChatConversation::getShopId).distinct().toList()));
        List<Long> distinctPeerIds = new ArrayList<>();
        for (ChatConversation c : convs) {
            distinctPeerIds.add(Objects.equals(c.getBuyerId(), userId) ? c.getShopUserId() : c.getBuyerId());
        }
        Map<Long, UserInfo> userById = indexUsers(fetchUsers(
                distinctPeerIds.stream().distinct().toList()));

        List<ConversationVO> result = new ArrayList<>();
        for (ChatConversation c : convs) {
            ConversationVO vo = new ConversationVO();
            vo.setConversationId(c.getId());
            vo.setShopId(c.getShopId());
            vo.setLastMessage(c.getLastMessage());
            vo.setLastTime(c.getLastTime());
            // 对方：我如果是买家 → 店主；否则（店主）→ 买家
            Long peerId = Objects.equals(c.getBuyerId(), userId) ? c.getShopUserId() : c.getBuyerId();
            vo.setPeer(toPeerPreview(peerId, userById.get(peerId)));
            // 店铺信息（店铺名/logo，买卖双方都展示店铺，识别上下文）；批量结果缺失则该字段留空
            ShopInfo shop = shopById.get(c.getShopId());
            if (shop != null) {
                vo.setShopName(shop.getShopName());
                vo.setShopLogo(shop.getShopLogo());
            }
            result.add(vo);
        }
        return result;
    }

    @Override
    public PageResult<MessageVO> messages(Long userId, Long conversationId, long page, long size) {
        requireUserId(userId);
        ChatConversation conv = requireParticipant(userId, conversationId);
        Page<ChatMessage> p = messageMapper.selectPage(new Page<>(page, size),
                Wrappers.<ChatMessage>lambdaQuery()
                        .eq(ChatMessage::getConversationId, conversationId)
                        .orderByDesc(ChatMessage::getId));
        List<ChatMessage> records = p.getRecords();
        // 本页发送者一次批量取用户信息（避免逐条 Feign 的 N+1），缺省用户在气泡侧不展示昵称/头像
        Map<Long, UserInfo> userById = indexUsers(fetchUsers(
                records.stream().map(ChatMessage::getSenderId).distinct().toList()));
        // 升序返回（最新的在最后一页），便于前端直接顺序渲染
        List<MessageVO> list = records.stream()
                .map(m -> toMessageVO(m, userById.get(m.getSenderId())))
                .sorted((a, b) -> Long.compare(a.getId(), b.getId()))
                .toList();
        return PageResult.of(list, p.getTotal(), page, size);
    }

    @Override
    public List<MessageVO> messagesSince(Long userId, Long conversationId, Long sinceId, int limit) {
        requireUserId(userId);
        requireParticipant(userId, conversationId);
        int n = Math.min(Math.max(limit, 1), SINCE_MAX_LIMIT);
        // 统一「按 id 倒序取最靠后的 n 条」再反转升序：
        // - sinceId 为空/≤0：取最新 n 条（首次进入会话或本地无历史）；
        // - 否则取 id > sinceId 的最近 n 条 —— 断线很久、漏掉的消息超过 n 条时，
        //   优先保留最新部分（前端发现返回条数达到上限会降级为全量重载，不会留空洞）。
        LambdaQueryWrapper<ChatMessage> query = Wrappers.<ChatMessage>lambdaQuery()
                .eq(ChatMessage::getConversationId, conversationId)
                .orderByDesc(ChatMessage::getId)
                .last("LIMIT " + n);
        if (sinceId != null && sinceId > 0) {
            query.gt(ChatMessage::getId, sinceId);
        }
        List<ChatMessage> records = messageMapper.selectList(query);
        if (records.isEmpty()) {
            return List.of();
        }
        // 批量取发送者信息，避免逐条 Feign；升序返回便于前端顺序渲染
        Map<Long, UserInfo> userById = indexUsers(fetchUsers(
                records.stream().map(ChatMessage::getSenderId).distinct().toList()));
        return records.stream()
                .map(m -> toMessageVO(m, userById.get(m.getSenderId())))
                .sorted((a, b) -> Long.compare(a.getId(), b.getId()))
                .toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public MessageVO sendMessage(Long userId, SendMessageDTO dto) {
        requireUserId(userId);
        // 发送频控：30 秒内最多 sendMaxPer30s 条，防刷屏
        if (!sendLimiter.allow("chat-send:" + userId, sendMaxPer30s, 30_000L)) {
            throw new BizException("发送过于频繁，请稍后再试");
        }
        ChatConversation conv = requireParticipant(userId, dto.getConversationId());
        Long receiverId = Objects.equals(conv.getBuyerId(), userId) ? conv.getShopUserId() : conv.getBuyerId();

        ChatMessage msg = new ChatMessage();
        msg.setConversationId(conv.getId());
        msg.setSenderId(userId);
        msg.setReceiverId(receiverId);
        msg.setContent(dto.getContent());
        msg.setMsgType(1);
        msg.setCreateTime(LocalDateTime.now());
        messageMapper.insert(msg);

        // 更新会话摘要
        ChatConversation update = new ChatConversation();
        update.setId(conv.getId());
        update.setLastMessage(dto.getContent());
        update.setLastTime(LocalDateTime.now());
        conversationMapper.updateById(update);

        MessageVO vo = toMessageVO(msg);
        // 回显客户端消息 ID（不入库）：前端据此把「发送中」的乐观气泡替换为正式消息
        vo.setClientMsgId(dto.getClientMsgId());
        log.info("聊天消息已持久化：conversationId={}, senderId={}", conv.getId(), userId);
        return vo;
    }

    // ==================== 私有辅助 ====================

    /** 获取对方用户信息（头像/昵称/手机号，会话窗口内展示）。 */
    private PeerInfo toPeer(Long userId) {
        return toPeer(userId, safeGetUser(userId));
    }

    /** 用「已取到的用户信息」构建对端对象；u 为 null（auth 异常/用户不存在）时兜底“用户+id”。 */
    private PeerInfo toPeer(Long userId, UserInfo u) {
        if (u == null) {
            PeerInfo empty = new PeerInfo();
            empty.setUserId(userId);
            empty.setNickname("用户" + userId);
            return empty;
        }
        PeerInfo peer = new PeerInfo();
        peer.setUserId(u.getId());
        peer.setNickname(u.getNickname());
        peer.setAvatar(u.getAvatar());
        peer.setPhone(u.getPhone());
        return peer;
    }

    /** 会话列表预览用的对端信息：仅 userId + 头像/昵称，**不**下发手机号（最小化外泄，与
     *  ConversationVO 预览语义一致）；手机号只在进入会话窗口（openConversation）时下发。 */
    private PeerInfo toPeerPreview(Long userId, UserInfo u) {
        if (u == null) {
            PeerInfo empty = new PeerInfo();
            empty.setUserId(userId);
            empty.setNickname("用户" + userId);
            return empty;
        }
        PeerInfo peer = new PeerInfo();
        peer.setUserId(u.getId());
        peer.setNickname(u.getNickname());
        peer.setAvatar(u.getAvatar());
        return peer;
    }

    // ==================== 批量 Feign + 降级（消会话列表/历史里的 N+1） ====================

    /** 批量取店铺信息；异常降级为空（店铺名/logo 缺失不拖垮会话列表）。 */
    private List<ShopInfo> fetchShops(List<Long> shopIds) {
        if (shopIds == null || shopIds.isEmpty()) {
            return List.of();
        }
        try {
            R<List<ShopInfo>> result = shopFeignClient.getShopsByIds(shopIds);
            return result == null || result.getData() == null ? List.of() : result.getData();
        } catch (Exception e) {
            log.warn("批量获取店铺信息失败，降级为空：ids={}, err={}", shopIds, e.getMessage());
            return List.of();
        }
    }

    /** 批量取用户信息；异常降级为空（缺省的 peer 由 toPeer(..., null) 兜底为“用户+id”）。 */
    private List<UserInfo> fetchUsers(List<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return List.of();
        }
        try {
            R<List<UserInfo>> result = authFeignClient.listUsers(userIds);
            return result == null || result.getData() == null ? List.of() : result.getData();
        } catch (Exception e) {
            log.warn("批量获取用户信息失败，降级为空：ids={}, err={}", userIds, e.getMessage());
            return List.of();
        }
    }

    private Map<Long, ShopInfo> indexShops(List<ShopInfo> list) {
        Map<Long, ShopInfo> map = new HashMap<>();
        for (ShopInfo s : list) {
            if (s != null && s.getId() != null) {
                map.put(s.getId(), s);
            }
        }
        return map;
    }

    private Map<Long, UserInfo> indexUsers(List<UserInfo> list) {
        Map<Long, UserInfo> map = new HashMap<>();
        for (UserInfo u : list) {
            if (u != null && u.getId() != null) {
                map.put(u.getId(), u);
            }
        }
        return map;
    }

    /**
     * 调用 auth 服务获取用户信息。
     *
     * <p>auth 服务异常（连接失败/超时/返回空）时降级返回 null 并记录警告日志，
     * 避免单点故障拖垮整个聊天会话/历史列表（调用方按 null 兜底展示）。</p>
     *
     * @param userId 用户 ID
     * @return 用户信息；异常或不存在时返回 null
     */
    private UserInfo safeGetUser(Long userId) {
        try {
            R<UserInfo> userResult = authFeignClient.getUser(userId);
            return userResult == null ? null : userResult.getData();
        } catch (Exception e) {
            log.warn("调用 auth 获取用户信息失败，降级返回空：userId={}, err={}", userId, e.getMessage());
            return null;
        }
    }

    /** 校验当前用户是会话参与者，返回会话 */
    private ChatConversation requireParticipant(Long userId, Long conversationId) {
        ChatConversation conv = conversationMapper.selectById(conversationId);
        if (conv == null) {
            throw new BizException("会话不存在");
        }
        if (!Objects.equals(conv.getBuyerId(), userId) && !Objects.equals(conv.getShopUserId(), userId)) {
            throw new BizException(ResultCode.FORBIDDEN.getCode(), "无权访问该会话");
        }
        return conv;
    }

    private void requireUserId(Long userId) {
        if (userId == null) {
            throw new BizException(ResultCode.UNAUTHORIZED.getCode(), "未登录或登录已过期");
        }
    }

    /** 单条消息转 VO（发送者用户信息实时单查，用于发送后即时回显）。 */
    private MessageVO toMessageVO(ChatMessage msg) {
        return toMessageVO(msg, safeGetUser(msg.getSenderId()));
    }

    /** 消息转 VO，发送者信息由调用方批量取好传入（历史分页用，避免逐条 Feign）。 */
    private MessageVO toMessageVO(ChatMessage msg, UserInfo user) {
        MessageVO vo = new MessageVO();
        vo.setId(msg.getId());
        vo.setConversationId(msg.getConversationId());
        vo.setSenderId(msg.getSenderId());
        vo.setContent(msg.getContent());
        vo.setCreateTime(msg.getCreateTime());
        // 发送者昵称/头像（展示消息气泡用；auth 异常时降级为不展示）
        if (user != null) {
            vo.setSenderNickname(user.getNickname());
            vo.setSenderAvatar(user.getAvatar());
        }
        return vo;
    }
}
