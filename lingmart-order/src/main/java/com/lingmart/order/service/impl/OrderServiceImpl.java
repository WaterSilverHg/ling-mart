package com.lingmart.order.service.impl;

import com.alibaba.csp.sentinel.annotation.SentinelResource;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import com.alibaba.csp.sentinel.slots.block.degrade.DegradeRule;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRule;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lingmart.common.dto.OrderPaidMessage;
import com.lingmart.common.dto.SaleItem;
import com.lingmart.common.dto.ShopInfo;
import com.lingmart.common.dto.SkuInfoDTO;
import com.lingmart.common.dto.StockLockItem;
import com.lingmart.common.dto.StockLockRequest;
import com.lingmart.common.exception.BizException;
import com.lingmart.common.result.PageResult;
import com.lingmart.common.result.R;
import com.lingmart.common.result.ResultCode;
import com.lingmart.order.config.RabbitMqConfig;
import com.lingmart.order.dto.OrderCreateDTO;
import com.lingmart.order.dto.OrderExportRow;
import com.lingmart.order.dto.OrderItemDTO;
import com.lingmart.order.entity.Order;
import com.lingmart.order.entity.OrderItem;
import com.lingmart.order.entity.OrderPaidEvent;
import com.lingmart.order.feign.ProductFeignClient;
import com.lingmart.order.feign.ShopFeignClient;
import com.lingmart.order.feign.WareFeignClient;
import com.lingmart.order.mapper.OrderItemMapper;
import com.lingmart.order.mapper.OrderMapper;
import com.lingmart.order.mapper.OrderPaidEventMapper;
import com.lingmart.order.service.OrderService;
import com.lingmart.order.util.OrderSnGenerator;
import com.lingmart.order.vo.OrderDetailVO;
import com.lingmart.order.vo.OrderItemVO;
import com.lingmart.order.vo.SellerStatsVO;
import io.seata.spring.annotation.GlobalTransactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.ibatis.cursor.Cursor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * 订单服务实现。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderServiceImpl implements OrderService {

    /** 订单状态 */
    private static final int ORDER_PENDING_PAY = 0;   // 待支付
    private static final int ORDER_PAID = 1;          // 已支付
    private static final int ORDER_SHIPPED = 2;       // 已发货
    private static final int ORDER_COMPLETED = 3;     // 已完成
    private static final int ORDER_CANCELLED = 4;     // 已取消
    private static final int ORDER_REFUNDED = 5;      // 已退款

    /** 退款状态 */
    private static final int REFUND_NONE = 0;         // 无退款
    private static final int REFUND_PENDING = 1;      // 退款中（买家已申请）
    private static final int REFUND_APPROVED = 2;     // 已退款
    private static final int REFUND_REJECTED = 3;     // 退款拒绝

    private final OrderMapper orderMapper;
    private final OrderItemMapper orderItemMapper;
    private final ProductFeignClient productFeignClient;
    private final WareFeignClient wareFeignClient;
    private final ShopFeignClient shopFeignClient;
    private final RabbitTemplate rabbitTemplate;
    private final OrderPaidEventMapper orderPaidEventMapper;
    private final ObjectMapper objectMapper;

    @Override
    @SentinelResource(value = "createOrder", blockHandler = "createOrderBlock")
    @GlobalTransactional(name = "createOrder", rollbackFor = Exception.class)
    @Transactional(rollbackFor = Exception.class)
    public String createOrder(Long buyerId, String idempotencyKey, OrderCreateDTO dto) {
        // 0. 幂等：相同幂等键（且属同一买家）直接返回已有订单
        if (StringUtils.hasText(idempotencyKey)) {
            Order exist = orderMapper.selectOne(new LambdaQueryWrapper<Order>()
                    .eq(Order::getIdempotencyKey, idempotencyKey)
                    .eq(Order::getBuyerId, buyerId)
                    .last("LIMIT 1"));
            if (exist != null) {
                log.info("幂等命中，返回已有订单：orderSn={}, key={}", exist.getOrderSn(), idempotencyKey);
                return exist.getOrderSn();
            }
        }

        // 1. 逐条获取 SKU 服务端快照（价格/标题/店铺，防客户端篡改）
        // 0.5 禁止自买：买家不能购买自己店铺的商品（防自买刷销量/刷信用）
        R<ShopInfo> myShopResult = shopFeignClient.getShopByUserId(buyerId);
        Long myShopId = myShopResult == null || myShopResult.getData() == null
                ? null : myShopResult.getData().getId();

        List<OrderItem> items = new ArrayList<>();
        Long shopId = null;
        BigDecimal total = BigDecimal.ZERO;
        for (OrderItemDTO itemDto : dto.getItems()) {
            SkuInfoDTO sku = R.checkOk(productFeignClient.getSkuInfo(itemDto.getSkuId()));
            if (sku == null) {
                throw new BizException("商品不存在：skuId=" + itemDto.getSkuId());
            }
            if (myShopId != null && Objects.equals(myShopId, sku.getShopId())) {
                throw new BizException("不能购买自己店铺的商品");
            }
            // 单店铺订单约束
            if (shopId == null) {
                shopId = sku.getShopId();
            } else if (!Objects.equals(shopId, sku.getShopId())) {
                throw new BizException("一次下单仅支持同一店铺的商品");
            }

            OrderItem item = new OrderItem();
            item.setSpuId(sku.getSpuId());
            item.setSkuId(sku.getSkuId());
            item.setShopId(sku.getShopId());
            item.setSpuTitle(sku.getSpuTitle());
            item.setSkuTitle(sku.getSkuTitle());
            item.setSkuImage(sku.getImage());
            item.setPrice(sku.getPrice());
            item.setNum(itemDto.getNum());
            item.setTotalPrice(sku.getPrice().multiply(BigDecimal.valueOf(itemDto.getNum())));
            total = total.add(item.getTotalPrice());
            items.add(item);
        }

        // 2. 建单（订单 + 明细）
        String orderSn = OrderSnGenerator.generate();
        Order order = new Order();
        order.setOrderSn(orderSn);
        order.setBuyerId(buyerId);
        order.setShopId(shopId);
        order.setTotalAmount(total);
        order.setPayStatus(0);
        order.setOrderStatus(ORDER_PENDING_PAY);
        order.setBuyerRemark(dto.getBuyerRemark());
        order.setIdempotencyKey(StringUtils.hasText(idempotencyKey) ? idempotencyKey : null);
        try {
            orderMapper.insert(order);
        } catch (DuplicateKeyException e) {
            // 并发下唯一键冲突：视为重复提交，返回已有订单（须按同一买家过滤，
            // 防止跨买家复用幂等键时错误返回他人的订单）
            Order exist = orderMapper.selectOne(new LambdaQueryWrapper<Order>()
                    .eq(Order::getIdempotencyKey, idempotencyKey)
                    .eq(Order::getBuyerId, buyerId)
                    .last("LIMIT 1"));
            if (exist != null) {
                log.warn("幂等并发冲突，返回已有订单：orderSn={}, key={}", exist.getOrderSn(), idempotencyKey);
                return exist.getOrderSn();
            }
            throw e;
        }
        for (OrderItem item : items) {
            item.setOrderId(order.getId());
            item.setOrderSn(orderSn);
            orderItemMapper.insert(item);
        }

        // 3. 锁库存（Seata 全局事务内，ware 分支随全局回滚）
        StockLockRequest lockRequest = new StockLockRequest();
        lockRequest.setOrderSn(orderSn);
        lockRequest.setItems(items.stream().map(this::toLockItem).toList());
        R.checkOk(wareFeignClient.lockStock(lockRequest));

        log.info("创建订单成功：orderSn={}, buyerId={}, amount={}", orderSn, buyerId, total);
        return orderSn;
    }

    @Override
    public void sendTimeoutMessage(String orderSn) {
        // 下单事务已提交，投递延迟消息；TTL 到期未支付由消费者自动取消。
        // MQ 不可用时仅记录日志，不阻断下单接口（超时自动取消可后续补偿）
        try {
            rabbitTemplate.convertAndSend(RabbitMqConfig.DELAY_EXCHANGE, RabbitMqConfig.DELAY_ROUTING_KEY, orderSn);
            log.info("已发送订单超时消息：orderSn={}", orderSn);
        } catch (Exception e) {
            log.warn("订单超时消息发送失败（不影响下单）：orderSn={}, err={}", orderSn, e.getMessage());
        }
    }

    @Override
    @GlobalTransactional(name = "payOrder", rollbackFor = Exception.class)
    @Transactional(rollbackFor = Exception.class)
    public void pay(Long buyerId, String orderSn) {
        Order order = requireOwnedOrder(orderSn, buyerId);
        Order update = new Order();
        update.setId(order.getId());
        update.setPayStatus(1);
        update.setOrderStatus(ORDER_PAID);
        update.setPayTime(LocalDateTime.now());
        // 状态流转走「带条件的原子更新」：与 reviewRefund 同一写法。旧实现是「先查后改」（updateById 无条件），
        // 一旦同单并发/重复进入本方法（网关重试、MQ 驱动化改造、或 Seata 降级），后面的 updateSale 会把销量
        // 重复累加（sale_count = sale_count + N，无幂等键）。这里把状态守卫作为唯一的并发闸门。
        int rows = orderMapper.update(update, new LambdaQueryWrapper<Order>()
                .eq(Order::getId, order.getId())
                .eq(Order::getOrderStatus, ORDER_PENDING_PAY));
        if (rows == 0) {
            throw new BizException("订单状态不允许支付");
        }
        // 销量同步累加（SPU/SKU）仍在本全局事务内，与订单状态原子（product 暂不事件化）
        R.checkOk(productFeignClient.updateSale(buildSaleItems(order)));
        // 库存扣减改为“支付成功事件 + ware 异步消费”：支付成功事件写入 Outbox（与订单状态同一本地事务，
        // 保证“订单已付”必有待发事件），提交后由 OrderPaidOutboxRelay 发布到 MQ；ware 幂等扣减
        persistPaidOutbox(order);
        log.info("订单支付成功：orderSn={}", orderSn);
    }

    /**
     * 把「支付成功」落一条 Outbox 待发记录（与订单状态同事务，保证必达；序列化失败视为支付处理失败整体回滚）。
     */
    private void persistPaidOutbox(Order order) {
        OrderPaidMessage message = toPaidMessage(buildLockRequest(order));
        try {
            OrderPaidEvent event = new OrderPaidEvent();
            event.setEventId(UUID.randomUUID().toString());
            event.setOrderSn(order.getOrderSn());
            event.setPayload(objectMapper.writeValueAsString(message));
            event.setStatus(0);
            orderPaidEventMapper.insert(event);
            log.info("支付成功事件已入 Outbox：orderSn={}, eventId={}", order.getOrderSn(), event.getEventId());
        } catch (JsonProcessingException e) {
            throw new BizException("支付事件序列化失败，无法继续");
        }
    }

    private OrderPaidMessage toPaidMessage(StockLockRequest request) {
        List<OrderPaidMessage.Item> items = (request.getItems() == null ? List.<OrderPaidMessage.Item>of()
                : request.getItems().stream()
                        .map(i -> new OrderPaidMessage.Item(i.getSkuId(), i.getShopId(), i.getNum()))
                        .toList());
        return new OrderPaidMessage(request.getOrderSn(), items);
    }

    @Override
    @GlobalTransactional(name = "cancelOrder", rollbackFor = Exception.class)
    @Transactional(rollbackFor = Exception.class)
    public void cancel(Long buyerId, String orderSn) {
        Order order = requireOwnedOrder(orderSn, buyerId);
        if (order.getOrderStatus() != ORDER_PENDING_PAY) {
            throw new BizException("订单状态不允许取消");
        }
        doCancel(order);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void confirm(Long buyerId, String orderSn) {
        Order order = requireOwnedOrder(orderSn, buyerId);
        if (order.getOrderStatus() != ORDER_SHIPPED) {
            throw new BizException("订单状态不允许确认收货");
        }
        Order update = new Order();
        update.setId(order.getId());
        update.setOrderStatus(ORDER_COMPLETED);
        update.setCompleteTime(LocalDateTime.now());
        orderMapper.updateById(update);
        log.info("订单确认收货：orderSn={}, buyerId={}", orderSn, buyerId);
    }

    @Override
    public void requestRefund(Long buyerId, String orderSn, String reason) {
        Order order = requireOwnedOrder(orderSn, buyerId);
        // 仅已支付/已发货/已完成可申请退款，且当前无退款记录（不含已拒绝——拒绝后可重新申请）
        if (order.getPayStatus() == null || order.getPayStatus() != 1) {
            throw new BizException("订单未支付，不能申请退款");
        }
        if (order.getRefundStatus() != null && order.getRefundStatus() == REFUND_APPROVED) {
            throw new BizException("该订单已退款");
        }
        if (order.getRefundStatus() != null && order.getRefundStatus() == REFUND_PENDING) {
            throw new BizException("已有退款申请审核中，请耐心等待");
        }
        if (!List.of(ORDER_PAID, ORDER_SHIPPED, ORDER_COMPLETED).contains(order.getOrderStatus())) {
            throw new BizException("当前订单状态不允许申请退款");
        }
        if (!StringUtils.hasText(reason)) {
            throw new BizException("请填写退款原因");
        }
        Order update = new Order();
        update.setId(order.getId());
        update.setRefundStatus(REFUND_PENDING);
        update.setRefundReason(reason);
        update.setRefundRejectReason(null); // 重新申请时清空上次拒绝原因
        orderMapper.updateById(update);
        log.info("买家申请退款：orderSn={}, buyerId={}, reason={}", orderSn, buyerId, reason);
    }

    @Override
    @GlobalTransactional(name = "refundOrder", rollbackFor = Exception.class)
    @Transactional(rollbackFor = Exception.class)
    public void reviewRefund(Long userId, String orderSn, boolean approve, String rejectReason) {
        ShopInfo shop = requireSellerShop(userId);
        Order order = orderMapper.selectOne(new LambdaQueryWrapper<Order>().eq(Order::getOrderSn, orderSn));
        if (order == null) {
            throw new BizException("订单不存在");
        }
        if (!Objects.equals(order.getShopId(), shop.getId())) {
            throw new BizException(ResultCode.FORBIDDEN.getCode(), "无权操作该订单");
        }
        if (order.getRefundStatus() == null || order.getRefundStatus() != REFUND_PENDING) {
            throw new BizException("该订单没有待审核的退款申请");
        }
        Order update = new Order();
        update.setId(order.getId());
        update.setRefundTime(LocalDateTime.now());
        if (approve) {
            // 通过：订单置已退款（不再计入销售统计）+ 库存回补 + 销量回退
            update.setRefundStatus(REFUND_APPROVED);
            update.setOrderStatus(ORDER_REFUNDED);
            // 带条件的原子更新：仅「待审核」可流转 —— 并发/重复点击时只有一个能进入本分支，
            // 从而保证库存回补与销量回退各自只执行一次（原「读-判断-写」并非原子）
            int rows = orderMapper.update(update, new LambdaQueryWrapper<Order>()
                    .eq(Order::getId, order.getId())
                    .eq(Order::getRefundStatus, REFUND_PENDING));
            if (rows == 0) {
                throw new BizException("该订单没有待审核的退款申请");
            }
            // 顺序不可调整：ware 是 Seata AT 分支（可随全局事务一起回滚）；
            // product 未引入 seata 客户端，其写入不受全局事务保护，必须放在最后，
            // 使「product 已回退而后续失败」的不一致窗口降为 0
            R.checkOk(wareFeignClient.restockStock(buildLockRequest(order)));
            R.checkOk(productFeignClient.rollbackSale(orderSn, buildSaleItems(order)));
            log.info("退款审核通过（已回补库存并回退销量）：orderSn={}, shopId={}", orderSn, shop.getId());
        } else {
            // 拒绝：仅记拒绝状态与原因，订单维持原状（买家可重新申请）
            if (!StringUtils.hasText(rejectReason)) {
                throw new BizException("请填写拒绝原因");
            }
            update.setRefundStatus(REFUND_REJECTED);
            update.setRefundRejectReason(rejectReason);
            orderMapper.updateById(update);
            log.info("退款审核拒绝：orderSn={}, reason={}", orderSn, rejectReason);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void ship(Long userId, String orderSn) {
        ShopInfo shop = requireSellerShop(userId);
        Order order = orderMapper.selectOne(new LambdaQueryWrapper<Order>().eq(Order::getOrderSn, orderSn));
        if (order == null) {
            throw new BizException("订单不存在");
        }
        if (!Objects.equals(order.getShopId(), shop.getId())) {
            throw new BizException(ResultCode.FORBIDDEN.getCode(), "无权操作该订单");
        }
        if (order.getOrderStatus() != ORDER_PAID) {
            throw new BizException("订单状态不允许发货");
        }
        Order update = new Order();
        update.setId(order.getId());
        update.setOrderStatus(ORDER_SHIPPED);
        update.setShipTime(LocalDateTime.now());
        orderMapper.updateById(update);
        log.info("订单发货：orderSn={}, shopId={}", orderSn, shop.getId());
    }

    @Override
    @GlobalTransactional(name = "cancelTimeoutOrder", rollbackFor = Exception.class)
    @Transactional(rollbackFor = Exception.class)
    public void cancelByTimeout(String orderSn) {
        Order order = orderMapper.selectOne(new LambdaQueryWrapper<Order>().eq(Order::getOrderSn, orderSn));
        if (order != null && order.getOrderStatus() == ORDER_PENDING_PAY) {
            doCancel(order);
            log.info("订单超时自动取消：orderSn={}", orderSn);
        }
    }

    @Override
    public PageResult<OrderDetailVO> buyerOrders(Long buyerId, long page, long size, Integer orderStatus) {
        Page<Order> p = orderMapper.selectPage(new Page<>(page, size),
                new LambdaQueryWrapper<Order>()
                        .eq(Order::getBuyerId, buyerId)
                        .eq(orderStatus != null, Order::getOrderStatus, orderStatus)
                        .orderByDesc(Order::getCreateTime));
        // 买家视角：按角色裁剪（见 toDetailVO(Order, boolean)），不下发自己填写的订单备注
        List<OrderDetailVO> vos = toDetailVOList(p.getRecords());
        vos.forEach(vo -> vo.setBuyerRemark(null));
        return PageResult.of(vos, p.getTotal(), page, size);
    }

    @Override
    public PageResult<OrderDetailVO> sellerOrders(Long userId, long page, long size) {
        ShopInfo shop = shopFeignClient.getShopByUserId(userId).getData();
        if (shop == null) {
            return PageResult.of(List.of(), 0, page, size);
        }
        Page<Order> p = orderMapper.selectPage(new Page<>(page, size),
                new LambdaQueryWrapper<Order>().eq(Order::getShopId, shop.getId()).orderByDesc(Order::getCreateTime));
        // 卖家视角：一次 in 取回当页全部明细（旧写法每单一次查询）
        return PageResult.of(toDetailVOList(p.getRecords()), p.getTotal(), page, size);
    }

    @Override
    public OrderDetailVO detail(Long userId, String orderSn) {
        Order order = orderMapper.selectOne(new LambdaQueryWrapper<Order>().eq(Order::getOrderSn, orderSn));
        if (order == null) {
            throw new BizException("订单不存在");
        }
        boolean isBuyer = Objects.equals(order.getBuyerId(), userId);
        ShopInfo shop = shopFeignClient.getShopByUserId(userId).getData();
        boolean isSeller = shop != null && Objects.equals(order.getShopId(), shop.getId());
        if (!isBuyer && !isSeller) {
            throw new BizException(ResultCode.FORBIDDEN.getCode(), "无权查看该订单");
        }
        return toDetailVO(order, isBuyer);
    }

    /** 支付超时时间（毫秒），来自 application.yml */
    @Value("${lingmart.order.timeout-ms:120000}")
    private long timeoutMs;

    /**
     * 超时订单兜底扫描：MQ 延迟消息丢失/发送失败时，仍能取消超时未支付订单。
     * 与 RabbitMQ TTL 取消互补，保证最终一致性。
     */
    @Scheduled(fixedDelayString = "${lingmart.order.scan-interval-ms:60000}")
    public void scanTimeoutOrders() {
        List<Order> overdue = orderMapper.selectList(new LambdaQueryWrapper<Order>()
                .eq(Order::getOrderStatus, ORDER_PENDING_PAY)
                .lt(Order::getCreateTime, LocalDateTime.now().minus(Duration.ofMillis(timeoutMs))));
        int cancelled = 0;
        for (Order order : overdue) {
            try {
                cancelByTimeout(order.getOrderSn());
                cancelled++;
            } catch (Exception e) {
                log.error("超时兜底取消失败：orderSn={}, err={}", order.getOrderSn(), e.getMessage());
            }
        }
        if (cancelled > 0) {
            log.info("超时兜底扫描：本次取消 {} 个订单", cancelled);
        }
    }

    // ==================== 私有辅助方法 ====================

    private Order requireOwnedOrder(String orderSn, Long buyerId) {
        Order order = orderMapper.selectOne(new LambdaQueryWrapper<Order>().eq(Order::getOrderSn, orderSn));
        if (order == null) {
            throw new BizException("订单不存在");
        }
        if (!Objects.equals(order.getBuyerId(), buyerId)) {
            throw new BizException(ResultCode.FORBIDDEN.getCode(), "无权操作该订单");
        }
        return order;
    }

    private ShopInfo requireSellerShop(Long userId) {
        ShopInfo shop = shopFeignClient.getShopByUserId(userId).getData();
        if (shop == null) {
            throw new BizException("暂未开店，无法发货");
        }
        return shop;
    }

    /** 取消订单：置已取消 + 解锁库存 */
    private void doCancel(Order order) {
        Order update = new Order();
        update.setId(order.getId());
        update.setOrderStatus(ORDER_CANCELLED);
        update.setCancelTime(LocalDateTime.now());
        orderMapper.updateById(update);
        R.checkOk(wareFeignClient.unlockStock(buildLockRequest(order)));
        log.info("订单已取消：orderSn={}", order.getOrderSn());
    }

    /** 由订单明细组装库存操作请求 */
    private StockLockRequest buildLockRequest(Order order) {
        List<OrderItem> items = orderItemMapper.selectList(
                new LambdaQueryWrapper<OrderItem>().eq(OrderItem::getOrderId, order.getId()));
        StockLockRequest request = new StockLockRequest();
        request.setOrderSn(order.getOrderSn());
        request.setItems(items.stream().map(this::toLockItem).toList());
        return request;
    }

    /** 由订单明细组装销量更新条目 */
    private List<SaleItem> buildSaleItems(Order order) {
        return orderItemMapper.selectList(
                        new LambdaQueryWrapper<OrderItem>().eq(OrderItem::getOrderId, order.getId()))
                .stream()
                .map(item -> {
                    SaleItem sale = new SaleItem();
                    sale.setSpuId(item.getSpuId());
                    sale.setSkuId(item.getSkuId());
                    sale.setNum(item.getNum());
                    return sale;
                })
                .toList();
    }

    private StockLockItem toLockItem(OrderItem item) {
        StockLockItem lockItem = new StockLockItem();
        lockItem.setSkuId(item.getSkuId());
        lockItem.setShopId(item.getShopId());
        lockItem.setNum(item.getNum());
        return lockItem;
    }

    /** 单条装配（详情路径）：明细按需查一次 order_item。 */
    private OrderDetailVO toDetailVO(Order order) {
        List<OrderItemVO> items = orderItemMapper.selectList(new LambdaQueryWrapper<OrderItem>()
                        .eq(OrderItem::getOrderId, order.getId()))
                .stream().map(this::toItemVO).toList();
        return toDetailVO(order, items);
    }

    /**
     * 批量装配（列表路径）：一次 {@code in(orderIds)} 取回当页全部明细再按 orderId 分组，
     * 消除「每张订单一次 order_item 查询」的 N+1（旧写法每页 10 单 = 10 次额外查询）。
     */
    private List<OrderDetailVO> toDetailVOList(List<Order> orders) {
        if (orders == null || orders.isEmpty()) {
            return List.of();
        }
        Map<Long, List<OrderItemVO>> itemMap = loadItemsByOrderId(orders);
        return orders.stream()
                .map(order -> toDetailVO(order, itemMap.getOrDefault(order.getId(), List.of())))
                .toList();
    }

    /** 按订单批量装载明细：返回 orderId → 明细 VO 列表（无明细的订单位缺席）。 */
    private Map<Long, List<OrderItemVO>> loadItemsByOrderId(List<Order> orders) {
        List<Long> orderIds = orders.stream().map(Order::getId).filter(Objects::nonNull).toList();
        if (orderIds.isEmpty()) {
            return Map.of();
        }
        return orderItemMapper.selectList(new LambdaQueryWrapper<OrderItem>()
                        .in(OrderItem::getOrderId, orderIds))
                .stream()
                .collect(Collectors.groupingBy(OrderItem::getOrderId,
                        Collectors.mapping(this::toItemVO, Collectors.toList())));
    }

    private OrderDetailVO toDetailVO(Order order, List<OrderItemVO> items) {
        OrderDetailVO vo = new OrderDetailVO();
        vo.setId(order.getId());
        vo.setOrderSn(order.getOrderSn());
        vo.setBuyerId(order.getBuyerId());
        vo.setShopId(order.getShopId());
        vo.setTotalAmount(order.getTotalAmount());
        vo.setPayStatus(order.getPayStatus());
        vo.setOrderStatus(order.getOrderStatus());
        vo.setRefundStatus(order.getRefundStatus());
        vo.setRefundReason(order.getRefundReason());
        vo.setRefundRejectReason(order.getRefundRejectReason());
        vo.setRefundTime(order.getRefundTime());
        vo.setBuyerRemark(order.getBuyerRemark());
        vo.setPayTime(order.getPayTime());
        vo.setShipTime(order.getShipTime());
        vo.setCompleteTime(order.getCompleteTime());
        vo.setCancelTime(order.getCancelTime());
        vo.setCreateTime(order.getCreateTime());
        vo.setItems(items);
        return vo;
    }

    /**
     * 按“查看方身份”裁剪的订单 VO。
     *
     * <p>数据最小化：`buyerRemark` 是买家写给卖家看的订单备注，买家查看自己的订单/列表时
     * 无需回显（前端列表/详情均不展示该字段），仅卖家需读取——故买家视角将其置空；
     * 退款原因/拒绝原因等买卖双方界面均需展示，不做裁剪。</p>
     */
    private OrderDetailVO toDetailVO(Order order, boolean viewerIsBuyer) {
        OrderDetailVO vo = toDetailVO(order);
        if (viewerIsBuyer) {
            vo.setBuyerRemark(null);
        }
        return vo;
    }

    private OrderItemVO toItemVO(OrderItem item) {
        OrderItemVO vo = new OrderItemVO();
        vo.setSpuId(item.getSpuId());
        vo.setSkuId(item.getSkuId());
        vo.setSpuTitle(item.getSpuTitle());
        vo.setSkuTitle(item.getSkuTitle());
        vo.setSkuImage(item.getSkuImage());
        vo.setPrice(item.getPrice());
        vo.setNum(item.getNum());
        vo.setTotalPrice(item.getTotalPrice());
        return vo;
    }

    @Override
    public SellerStatsVO sellerStats(Long userId, String range) {
        // 范围归一化：7d / 30d / all（缺省 7 天）
        String norm = (range == null || range.isBlank()) ? "7d" : range;
        int days = switch (norm) {
            case "30d" -> 30;
            case "all" -> 0;
            default -> 7;
        };
        LocalDateTime since = days > 0 ? LocalDateTime.now().minusDays(days) : null;

        SellerStatsVO vo = new SellerStatsVO();
        vo.setRange(norm);
        ShopInfo shop = resolveSellerShop(userId);
        if (shop == null) {
            vo.setOverview(emptyOverview());
            vo.setStatusDistribution(List.of());
            vo.setSalesTrend(List.of());
            vo.setTopProducts(List.of());
            return vo;
        }
        Long shopId = shop.getId();

        SellerStatsVO.Overview overview = orderMapper.selectSellerOverview(shopId, since);
        if (overview == null) {
            overview = new SellerStatsVO.Overview();
        }
        overview.setItemCount(orderItemMapper.selectSellerItemCount(shopId, since));
        long orderCount = overview.getOrderCount() == null ? 0 : overview.getOrderCount();
        overview.setAvgAmount(orderCount == 0 ? BigDecimal.ZERO
                : overview.getSalesAmount().divide(BigDecimal.valueOf(orderCount), 2, RoundingMode.HALF_UP));
        vo.setOverview(overview);

        vo.setStatusDistribution(orderMapper.selectStatusDistribution(shopId, since));
        vo.setSalesTrend(orderMapper.selectSalesTrend(shopId, since));
        vo.setTopProducts(orderItemMapper.selectTopProducts(shopId, since));
        log.info("卖家数据统计：userId={}, range={}, orderCount={}", userId, norm, overview.getOrderCount());
        return vo;
    }

    @Override
    @Transactional(readOnly = true)
    public void streamSellerOrders(Long userId, int days, Consumer<OrderExportRow> rowConsumer) {
        ShopInfo shop = resolveSellerShop(userId);
        if (shop == null) {
            return;
        }
        // DB 游标逐行读取，行内补中文状态文本后交回调写出，内存占用恒定
        try (Cursor<OrderExportRow> cursor = orderMapper.selectSellerOrdersForExport(shop.getId(), sinceOfDays(days))) {
            for (OrderExportRow row : cursor) {
                row.setStatusText(statusText(row.getOrderStatus()));
                rowConsumer.accept(row);
            }
        } catch (IOException e) {
            // Cursor.close() 声明抛 IOException；流式回调无法上抛受检异常，转业务异常
            log.error("卖家订单导出数据读取中断：userId={}, err={}", userId, e.getMessage());
            throw new BizException("导出数据读取中断，请重试");
        }
    }

    @Override
    @Transactional(readOnly = true)
    public void streamBuyerOrders(Long buyerId, int days, Consumer<OrderExportRow> rowConsumer) {
        if (buyerId == null) {
            throw new BizException(ResultCode.UNAUTHORIZED.getCode(), "未登录或登录已过期");
        }
        try (Cursor<OrderExportRow> cursor = orderMapper.selectBuyerOrdersForExport(buyerId, sinceOfDays(days))) {
            for (OrderExportRow row : cursor) {
                row.setStatusText(statusText(row.getOrderStatus()));
                rowConsumer.accept(row);
            }
        } catch (IOException e) {
            log.error("买家订单导出数据读取中断：buyerId={}, err={}", buyerId, e.getMessage());
            throw new BizException("导出数据读取中断，请重试");
        }
    }

    // ==================== 统计/导出辅助方法 ====================

    /** 解析当前用户店铺（未开店返回 null） */
    private ShopInfo resolveSellerShop(Long userId) {
        if (userId == null) {
            return null;
        }
        R<ShopInfo> result = shopFeignClient.getShopByUserId(userId);
        return result == null ? null : result.getData();
    }

    /** 导出起始时间：days &le; 0 表示不限（全部） */
    private LocalDateTime sinceOfDays(int days) {
        return days > 0 ? LocalDateTime.now().minusDays(days) : null;
    }

    private String statusText(Integer status) {
        return switch (status == null ? -1 : status) {
            case 0 -> "待支付";
            case 1 -> "已支付";
            case 2 -> "已发货";
            case 3 -> "已完成";
            case 4 -> "已取消";
            case 5 -> "已退款";
            default -> "未知";
        };
    }

    private SellerStatsVO.Overview emptyOverview() {
        SellerStatsVO.Overview o = new SellerStatsVO.Overview();
        o.setOrderCount(0L);
        o.setSalesAmount(BigDecimal.ZERO);
        o.setItemCount(0L);
        o.setAvgAmount(BigDecimal.ZERO);
        return o;
    }

    /**
     * Sentinel 限流降级：下单过于频繁时抛业务异常（由全局异常处理器转 R JSON 返回）。
     * 签名须与 {@link #createOrder} 一致并追加 BlockException。
     */
    public static String createOrderBlock(Long buyerId, String idempotencyKey,
                                          OrderCreateDTO dto, BlockException ex) {
        double qpsLimit = 0;
        if (ex.getRule() instanceof FlowRule) {
            qpsLimit = ((FlowRule) ex.getRule()).getCount();
        } else if (ex.getRule() instanceof DegradeRule) {
            // 处理降级规则（如果需要）
            qpsLimit = ((DegradeRule) ex.getRule()).getCount(); // DegradeRule 也有 getCount()
        }
        log.warn("下单被限流：buyerId={}, qpsLimit={}", buyerId, qpsLimit);
        throw new BizException("下单过于频繁，请稍后再试");
    }
}
