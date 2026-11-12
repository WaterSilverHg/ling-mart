package com.lingmart.order.service.impl;

import com.alibaba.csp.sentinel.annotation.SentinelResource;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import com.alibaba.csp.sentinel.slots.block.degrade.DegradeRule;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRule;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.support.SFunction;
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
import com.lingmart.order.dto.ShipDTO;
import com.lingmart.order.entity.Order;
import com.lingmart.order.entity.OrderItem;
import com.lingmart.order.entity.OrderPaidEvent;
import com.lingmart.order.entity.UserAddress;
import com.lingmart.order.feign.ProductFeignClient;
import com.lingmart.order.feign.ShopFeignClient;
import com.lingmart.order.feign.WareFeignClient;
import com.lingmart.order.mapper.OrderItemMapper;
import com.lingmart.order.mapper.OrderMapper;
import com.lingmart.order.mapper.OrderPaidEventMapper;
import com.lingmart.order.service.AddressService;
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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
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

    /** 发货方式（v0.27）：必须显式选择，不复用 order_status */
    /** 履约方式：快递（买家下单时选择，需要收货地址） */
    private static final int DELIVERY_EXPRESS = 1;

    /** 履约方式：到店自提（买家下单时选择，无需收货地址，改为快照门店信息） */
    private static final int DELIVERY_PICKUP = 2;

    private final OrderMapper orderMapper;
    private final OrderItemMapper orderItemMapper;
    private final ProductFeignClient productFeignClient;
    private final WareFeignClient wareFeignClient;
    private final ShopFeignClient shopFeignClient;
    private final RabbitTemplate rabbitTemplate;
    private final OrderPaidEventMapper orderPaidEventMapper;
    private final ObjectMapper objectMapper;
    private final AddressService addressService;

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
                // 结构化失败日志：压测/排障时按 buyerId、skuId、reason 聚合，不再只有一句异常文案
                log.warn("下单失败：buyerId={}, skuId={}, reason=商品不存在", buyerId, itemDto.getSkuId());
                throw new BizException("商品不存在：skuId=" + itemDto.getSkuId());
            }
            if (myShopId != null && Objects.equals(myShopId, sku.getShopId())) {
                log.warn("下单失败：buyerId={}, skuId={}, reason=不能购买自己店铺的商品", buyerId, itemDto.getSkuId());
                throw new BizException("不能购买自己店铺的商品");
            }
            // 单店铺订单约束
            if (shopId == null) {
                shopId = sku.getShopId();
            } else if (!Objects.equals(shopId, sku.getShopId())) {
                log.warn("下单失败：buyerId={}, skuId={}, shopId={}, firstShopId={}, reason=一次下单仅支持同一店铺的商品",
                        buyerId, itemDto.getSkuId(), sku.getShopId(), shopId);
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

        // 2. 解析履约方式与履约信息（全部在建单之前完成，失败时不留半条订单）
        //    自营实体店以「到店自提」为默认；快递能不能选由店铺的 supportExpress 决定
        int deliveryMode = resolveDeliveryMode(dto.getDeliveryMode());
        // R.checkOk 已经解包出 data（返回 T 而非 R），不要再调 getData()
        ShopInfo sellerShop = R.checkOk(shopFeignClient.getShopById(shopId));
        if (sellerShop == null) {
            throw new BizException("店铺不存在或已停业");
        }
        UserAddress address = null;
        if (deliveryMode == DELIVERY_EXPRESS) {
            if (!Integer.valueOf(1).equals(sellerShop.getSupportExpress())) {
                log.warn("下单失败：buyerId={}, shopId={}, reason=店铺不支持快递", buyerId, shopId);
                throw new BizException("该店铺不支持快递发货，请选择到店自提");
            }
            address = addressService.resolveForCheckout(buyerId, dto.getAddressId());
        } else if (!StringUtils.hasText(sellerShop.getAddress())) {
            // 自提必须有门店地址，否则买家不知道该去哪儿取货
            log.warn("下单失败：buyerId={}, shopId={}, reason=店铺未配置自提地址", buyerId, shopId);
            throw new BizException("该店铺尚未配置自提地址，暂时无法下单");
        }

        // 3. 建单（订单 + 明细）
        String orderSn = OrderSnGenerator.generate();
        Order order = new Order();
        order.setOrderSn(orderSn);
        order.setBuyerId(buyerId);
        order.setShopId(shopId);
        order.setTotalAmount(total);
        order.setPayStatus(0);
        order.setOrderStatus(ORDER_PENDING_PAY);
        order.setBuyerRemark(dto.getBuyerRemark());
        order.setDeliveryMode(deliveryMode);
        if (address != null) {
            // 快递：收货信息是快照，地址簿后续被修改或删除都不影响这张订单
            order.setAddressId(address.getId());
            order.setReceiverName(address.getReceiverName());
            order.setReceiverPhone(address.getReceiverPhone());
            order.setReceiverRegion(address.getProvince() + " " + address.getCity() + " " + address.getDistrict());
            order.setReceiverDetail(address.getDetailAddress());
        } else {
            // 自提：快照门店信息，买家之后在该订单里就能直接看到去哪儿取货、打哪个电话
            order.setPickupShopName(sellerShop.getShopName());
            order.setPickupAddress(sellerShop.getAddress());
            order.setPickupPhone(sellerShop.getContactPhone());
        }
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

        // 4. 锁库存（Seata 全局事务内，ware 分支随全局回滚）
        StockLockRequest lockRequest = new StockLockRequest();
        lockRequest.setOrderSn(orderSn);
        lockRequest.setItems(items.stream().map(this::toLockItem).toList());
        try {
            R.checkOk(wareFeignClient.lockStock(lockRequest));
        } catch (RuntimeException e) {
            // 压测里最大的一类失败就在这里（库存不足 / ware 超时 / 全局锁等待）。
            // 先把订单号与 SKU 落下来，异常再照常抛给全局处理器——不吞、不改变事务语义。
            log.warn("下单失败：buyerId={}, orderSn={}, skus={}, reason=锁库存失败: {}",
                    buyerId, orderSn, items.stream().map(OrderItem::getSkuId).toList(), e.getMessage());
            throw e;
        }

        // 故障注入（默认关闭）：模拟「ware 分支已成功、全局事务随后失败」，
        // 用来验证 undo_log 是否把 ware 的锁定真正回滚掉。仅验证用，勿在生产开启。
        if (faultInjectionRequested()) {
            log.warn("故障注入触发：orderSn={}, buyerId={}, skus={} —— 锁库存后强制失败",
                    orderSn, buyerId, items.stream().map(OrderItem::getSkuId).toList());
            throw new IllegalStateException("FAULT_INJECTION: after-lock");
        }

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
        // 状态守卫是唯一的并发闸门（实现见 transitionOrSkip）：旧实现是「先查后改」（updateById 无条件），
        // 一旦重复进入本方法（网关重试、MQ 驱动化改造、或 Seata 降级），后面的 updateSale 会把销量
        // 重复累加（sale_count = sale_count + N，无幂等键）
        transitionOrThrow(update, Order::getOrderStatus, List.of(ORDER_PENDING_PAY), "订单状态不允许支付");
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
        // 不再「先读后判」（读了也可能立刻被支付改变）：doCancel 内以条件更新做原子守卫，
        // 返回 false 说明该单已被支付或已取消，此时不得解锁库存
        if (!doCancel(order)) {
            throw new BizException("订单状态不允许取消");
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void confirm(Long buyerId, String orderSn) {
        Order order = requireOwnedOrder(orderSn, buyerId);
        Order update = new Order();
        update.setId(order.getId());
        update.setOrderStatus(ORDER_COMPLETED);
        update.setCompleteTime(LocalDateTime.now());
        transitionOrThrow(update, Order::getOrderStatus, List.of(ORDER_SHIPPED), "订单状态不允许确认收货");
        log.info("订单确认收货：orderSn={}, buyerId={}", orderSn, buyerId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void confirmBySystem(String orderSn) {
        Order order = orderMapper.selectOne(new LambdaQueryWrapper<Order>().eq(Order::getOrderSn, orderSn));
        if (order == null) {
            return;
        }
        // 退款进行中不自动确认收货：买家已经在走售后，系统再把它推到「已完成」会让退款审核语义变乱
        if (order.getRefundStatus() != null && order.getRefundStatus() != REFUND_NONE) {
            log.debug("退款流程进行中，跳过自动确认收货：orderSn={}, refundStatus={}", orderSn, order.getRefundStatus());
            return;
        }
        Order update = new Order();
        update.setId(order.getId());
        update.setOrderStatus(ORDER_COMPLETED);
        update.setCompleteTime(LocalDateTime.now());
        // 与买家手动确认共用同一个原子守卫：并发时只有一方能命中，另一方静默跳过
        if (!transitionOrSkip(update, Order::getOrderStatus, List.of(ORDER_SHIPPED))) {
            log.debug("系统自动确认收货跳过：状态已变更，orderSn={}", orderSn);
            return;
        }
        log.info("发货超时，系统自动确认收货：orderSn={}, buyerId={}", orderSn, order.getBuyerId());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
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
        // 退款申请时间：卖家超时未处理的计时锚点（与 refundTime「处理时间」区分）
        update.setRefundApplyTime(LocalDateTime.now());
        // 原子守卫：仅「无退款(0)」或「已拒绝(3)」可申请。上面的预检只为给出更精确的提示语，
        // 真正的并发闸门在这里——两次并发申请只有一次能命中，不会出现两条「退款中」记录。
        // 第三个参数把「上次拒绝原因」置 null：entity 里置 null 无效（NOT_NULL 策略），必须经 wrapper
        if (!transitionOrSkip(update, Order::getRefundStatus, List.of(REFUND_NONE, REFUND_REJECTED),
                Order::getRefundRejectReason)) {
            throw new BizException("已有退款申请审核中，请耐心等待");
        }
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
        if (approve) {
            // 通过：订单置已退款 + 库存回补 + 销量回退。与「超时自动同意」共用同一实现，见 approveRefund
            if (!approveRefund(order)) {
                throw new BizException("该订单没有待审核的退款申请");
            }
        } else {
            // 拒绝：仅记拒绝状态与原因，订单维持原状（买家可重新申请）
            if (!StringUtils.hasText(rejectReason)) {
                throw new BizException("请填写拒绝原因");
            }
            Order update = new Order();
            update.setId(order.getId());
            update.setRefundTime(LocalDateTime.now());
            update.setRefundStatus(REFUND_REJECTED);
            update.setRefundRejectReason(rejectReason);
            // 同样加原子守卫：与「通过」分支并发时只有一个能成功，
            // 否则会出现「库存已回补、订单已退款，但 refund_status 被覆盖成已拒绝」的矛盾状态
            transitionOrThrow(update, Order::getRefundStatus, List.of(REFUND_PENDING),
                    "该订单没有待审核的退款申请");
            log.info("退款审核拒绝：orderSn={}, reason={}", orderSn, rejectReason);
        }
    }

    /**
     * 退款通过（卖家手动审核 / 系统超时自动 共用）。
     *
     * <p>抽成一个方法的原因：两条路径的**守卫与副作用必须完全一致**——订单置已退款、
     * 库存回补、销量回退，以及「并发时只有一方能命中」的原子守卫。各写一份迟早会漂移，
     * 而漂移的后果是库存与销量被重复回补/回退。</p>
     *
     * @param order 待处理的订单（方法内以原子守卫再确认一次状态，不依赖调用方的预检）
     * @return true=本次通过生效；false=状态已被并发改动（卖家刚处理过/重复触发），未做任何写入
     */
    private boolean approveRefund(Order order) {
        Order update = new Order();
        update.setId(order.getId());
        update.setRefundTime(LocalDateTime.now());
        update.setRefundStatus(REFUND_APPROVED);
        update.setOrderStatus(ORDER_REFUNDED);
        // 原子守卫：仅「退款中」可流转 —— 并发/重复触发时只有一个能进入本分支，
        // 保证库存回补与销量回退各自只执行一次（原「读-判断-写」并非原子）。
        // 返回 boolean 而非直接抛异常：手动路径要把「未命中」当错误告诉卖家，
        // 自动路径则视为正常竞态（卖家恰好在扫描前处理了）静默跳过
        if (!transitionOrSkip(update, Order::getRefundStatus, List.of(REFUND_PENDING))) {
            return false;
        }
        // ware 与 product 均为 Seata AT 分支（product 已接入 seata starter），
        // 两者的写入都随本全局事务一起提交或回滚，故调用顺序不再影响一致性
        R.checkOk(wareFeignClient.restockStock(buildLockRequest(order)));
        R.checkOk(productFeignClient.rollbackSale(order.getOrderSn(), buildSaleItems(order)));
        log.info("退款已通过（已回补库存并回退销量）：orderSn={}, shopId={}", order.getOrderSn(), order.getShopId());
        return true;
    }

    /**
     * 单笔退款的超时自动同意（v0.27）。
     *
     * <p>与 {@code cancelByTimeout} 同构：没有卖家上下文，故不做店铺归属校验；
     * 状态守卫由 {@link #approveRefund} 内部完成，与卖家手动审核并发时只有一方能命中。</p>
     *
     * <p>由 {@code RefundAutoApproveJob} 调用（**必须经 Spring 代理调用**，
     * 否则本方法上的事务注解会因自调用而失效——订单服务里的 {@code scanTimeoutOrders}
     * 就踩过这个坑）。</p>
     */
    @Override
    @GlobalTransactional(name = "autoApproveRefund", rollbackFor = Exception.class)
    @Transactional(rollbackFor = Exception.class)
    public void autoApproveTimeoutRefund(String orderSn) {
        Order order = orderMapper.selectOne(new LambdaQueryWrapper<Order>().eq(Order::getOrderSn, orderSn));
        if (order == null || order.getRefundStatus() == null || order.getRefundStatus() != REFUND_PENDING) {
            log.debug("退款超时自动同意跳过：订单不存在或退款状态已变更，orderSn={}", orderSn);
            return;
        }
        if (!approveRefund(order)) {
            // 从上面的预检到守卫之间存在窗口（卖家恰好在这期间处理了）——属正常竞态，不报错
            log.debug("退款超时自动同意跳过：守卫未命中（已被卖家处理），orderSn={}", orderSn);
            return;
        }
        log.info("退款申请超时未处理，已自动同意：orderSn={}, 申请时间={}", orderSn, order.getRefundApplyTime());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void ship(Long userId, String orderSn, ShipDTO dto) {
        ShopInfo shop = requireSellerShop(userId);
        Order order = orderMapper.selectOne(new LambdaQueryWrapper<Order>().eq(Order::getOrderSn, orderSn));
        if (order == null) {
            throw new BizException("订单不存在");
        }
        if (!Objects.equals(order.getShopId(), shop.getId())) {
            throw new BizException(ResultCode.FORBIDDEN.getCode(), "无权操作该订单");
        }
        // 履约方式在下单时就定了，卖家不能再改（改方式等于换了一个交易）。
        // 只有本次升级前创建的存量订单 delivery_mode 为空，此时回退到请求里传的值。
        int deliveryMode = resolveShipMode(order, dto);
        validateShip(deliveryMode, dto);
        boolean express = deliveryMode == DELIVERY_EXPRESS;

        Order update = new Order();
        update.setId(order.getId());
        update.setOrderStatus(ORDER_SHIPPED);
        update.setShipTime(LocalDateTime.now());
        if (order.getDeliveryMode() == null) {
            // 存量订单：借这次发货把履约方式补齐，之后的展示/统计口径就统一了
            update.setDeliveryMode(deliveryMode);
        }
        // 自提订单不落快递信息（validateShip 已保证此时为空，这里再显式置 null 以免脏数据）
        update.setShippingCompany(express ? dto.getShippingCompany().trim() : null);
        update.setTrackingNo(express ? dto.getTrackingNo().trim() : null);
        transitionOrThrow(update, Order::getOrderStatus, List.of(ORDER_PAID), "订单状态不允许发货");
        log.info("订单发货：orderSn={}, shopId={}, 方式={}", orderSn, shop.getId(),
                express ? "快递 " + update.getShippingCompany() + " " + update.getTrackingNo()
                        : "到店自提（备货完成，等待买家取货）");
    }

    /**
     * 解析买家在下单时选择的履约方式。
     *
     * <p><b>不传时默认「到店自提」</b>：项目定位是自营实体店，自提是默认履约方式；
     * 这也让升级前的客户端与压测脚本无需改动即可继续下单（它们不带这个字段）。</p>
     *
     * @param requested 请求里的 deliveryMode，可为 null
     * @return 1 快递 / 2 到店自提
     */
    private static int resolveDeliveryMode(Integer requested) {
        if (requested == null) {
            return DELIVERY_PICKUP;
        }
        if (requested == DELIVERY_EXPRESS || requested == DELIVERY_PICKUP) {
            return requested;
        }
        throw new BizException("履约方式不合法");
    }

    /**
     * 解析本次发货对应的履约方式。
     *
     * <p>正常流程直接取订单上的值（下单时定的），并**校验请求里若带了 deliveryMode 必须一致**——
     * 否则卖家可以把自提订单发成快递，或者反过来。存量订单（{@code delivery_mode} 为空）
     * 没有记录过履约方式，此时才允许由请求指定。</p>
     *
     * @param order 订单
     * @param dto   发货参数（deliveryMode 可为空）
     * @return 1 快递 / 2 到店自提
     */
    private int resolveShipMode(Order order, ShipDTO dto) {
        Integer requested = dto == null ? null : dto.getDeliveryMode();
        if (order.getDeliveryMode() == null) {
            if (requested == null) {
                throw new BizException("请选择发货方式");
            }
            if (requested != DELIVERY_EXPRESS && requested != DELIVERY_PICKUP) {
                throw new BizException("发货方式不合法");
            }
            return requested;
        }
        if (requested != null && !requested.equals(order.getDeliveryMode())) {
            throw new BizException("该订单的履约方式为"
                    + (order.getDeliveryMode() == DELIVERY_EXPRESS ? "快递" : "到店自提") + "，不能更改");
        }
        return order.getDeliveryMode();
    }

    /**
     * 校验发货参数。
     *
     * <p>「选了快递才要单号」是**字段之间的条件依赖**，注解表达不了
     * （{@code @NotBlank} 无法写成「仅当 deliveryMode=1 时必填」），故在此显式校验。</p>
     *
     * <p>「自提却传了单号」选择**报错而非静默丢弃**：本项目一贯避免静默吞错，
     * 静默丢弃会掩盖前端的 bug，也会让库里出现语义矛盾的数据。</p>
     *
     * @param deliveryMode 订单的履约方式（1 快递 / 2 到店自提）
     * @param dto          发货参数
     */
    private void validateShip(int deliveryMode, ShipDTO dto) {
        if (dto == null) {
            throw new BizException("请填写发货信息");
        }
        boolean hasCompany = StringUtils.hasText(dto.getShippingCompany());
        boolean hasTracking = StringUtils.hasText(dto.getTrackingNo());
        if (deliveryMode == DELIVERY_EXPRESS) {
            if (!hasCompany) {
                throw new BizException("快递发货请填写快递公司");
            }
            if (!hasTracking) {
                throw new BizException("快递发货请填写运单号");
            }
        } else if (deliveryMode == DELIVERY_PICKUP) {
            if (hasCompany || hasTracking) {
                throw new BizException("到店自提的订单无需填写快递公司与运单号");
            }
        }
    }

    @Override
    @GlobalTransactional(name = "cancelTimeoutOrder", rollbackFor = Exception.class)
    @Transactional(rollbackFor = Exception.class)
    public void cancelByTimeout(String orderSn) {
        Order order = orderMapper.selectOne(new LambdaQueryWrapper<Order>().eq(Order::getOrderSn, orderSn));
        if (order == null) {
            return;
        }
        // 兜底路径：本方法由 MQ 延迟消息与定时扫描双路触发，同一单可能被并发处理，
        // 且订单可能已被用户支付/取消 —— 状态守卫未命中属正常情况，静默跳过而非抛异常
        // （抛异常会中断整轮超时扫描）
        if (doCancel(order)) {
            log.info("订单超时自动取消：orderSn={}", orderSn);
        } else {
            log.debug("超时取消跳过：订单状态已变更，orderSn={}", orderSn);
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

    /**
     * 故障注入开关（默认关）。
     *
     * <p>仅在验证 Seata 全局回滚时临时打开（{@code LINGMART_ORDER_FAULT_INJECTION_ENABLED=true}），
     * 配合请求头 {@code X-Fault-Inject: after-lock} 在锁库存之后强制抛异常，
     * 用来观察 {@code locked_stock}/{@code ware_order_task} 是否被 undo_log 回滚干净。
     * 具体步骤见 {@code scripts/verify-seata-rollback.sh}。</p>
     */
    @Value("${lingmart.order.fault-injection.enabled:false}")
    private boolean faultInjectionEnabled;

    // ==================== 私有辅助方法 ====================

    /**
     * 本次请求是否要求故障注入。
     *
     * <p>双重条件：配置开关打开（默认关）+ 请求头精确匹配。开关关闭时连请求头都不读，
     * 生产环境下即使有人带了这个头也不会有任何影响。</p>
     */
    private boolean faultInjectionRequested() {
        if (!faultInjectionEnabled) {
            return false;
        }
        RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
        if (!(attrs instanceof ServletRequestAttributes servlet)) {
            return false;
        }
        return "after-lock".equalsIgnoreCase(servlet.getRequest().getHeader("X-Fault-Inject"));
    }

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

    /**
     * 状态守卫流转（静默版）：仅当 {@code guard} 字段的当前值 ∈ {@code expectValues} 时才写入。
     *
     * <p>把「状态判断」从 Java 的 if 挪进 SQL 的 WHERE，使其与写入成为一个原子操作——
     * 这是订单状态机唯一的并发闸门。原「先查后改」（{@code updateById} 无条件）在并发下
     * 两个请求都能通过前置检查，从而各自执行后续副作用（取消订单时扣了库存、支付时重复累加销量）。
     * 由于副作用无法随「后写覆盖先写」一起撤销，必须在写入这一步就分出胜负。</p>
     *
     * @return true=已流转；false=状态已被并发改动（调用方自行决定抛异常还是静默跳过）
     */
    private boolean transitionOrSkip(Order update, SFunction<Order, Integer> guard, List<Integer> expectValues) {
        return transitionOrSkip(update, guard, expectValues, null);
    }

    /**
     * 同 {@link #transitionOrSkip(Order, SFunction, List)}，并把 {@code clearField} 这一列**置为 null**。
     *
     * <p>为什么需要这个参数：MyBatis-Plus 的 entity 更新默认走 NOT_NULL 策略，
     * entity 中为 null 的字段**不会进入 SET 子句**——想清空一列只能靠 UpdateWrapper 的
     * {@code set(field, null)}。原先 {@code requestRefund} 里写的是
     * {@code update.setRefundRejectReason(null)} 并注释「重新申请时清空上次拒绝原因」，
     * 那行注释承诺了行为、代码却没做到（买家重新申请后被拒，库里仍留着上一次的原因）。</p>
     */
    private boolean transitionOrSkip(Order update, SFunction<Order, Integer> guard, List<Integer> expectValues,
                                     SFunction<Order, ?> clearField) {
        LambdaUpdateWrapper<Order> wrapper = new LambdaUpdateWrapper<Order>()
                .eq(Order::getId, update.getId())
                .in(guard, expectValues);
        if (clearField != null) {
            wrapper.set(clearField, null);
        }
        return orderMapper.update(update, wrapper) > 0;
    }

    /**
     * 状态守卫流转（严格版）：未命中即抛 {@link BizException}。
     *
     * @param rejectMsg 未命中时的提示语（面向用户，与各调用点原有话术保持一致）
     */
    private void transitionOrThrow(Order update, SFunction<Order, Integer> guard,
                                   List<Integer> expectValues, String rejectMsg) {
        if (!transitionOrSkip(update, guard, expectValues)) {
            throw new BizException(rejectMsg);
        }
    }

    /**
     * 取消订单：置已取消 + 解锁库存。
     *
     * @return true=本次取消生效；false=状态已变（已被支付/已取消），未写库也未解锁库存
     */
    private boolean doCancel(Order order) {
        Order update = new Order();
        update.setId(order.getId());
        update.setOrderStatus(ORDER_CANCELLED);
        update.setCancelTime(LocalDateTime.now());
        if (!transitionOrSkip(update, Order::getOrderStatus, List.of(ORDER_PENDING_PAY))) {
            return false;
        }
        R.checkOk(wareFeignClient.unlockStock(buildLockRequest(order)));
        log.info("订单已取消：orderSn={}", order.getOrderSn());
        return true;
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

    /** null 转空串（拼接收货地址时避免出现 "null" 字样）。 */
    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
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
        vo.setRefundApplyTime(order.getRefundApplyTime());
        vo.setBuyerRemark(order.getBuyerRemark());
        vo.setPayTime(order.getPayTime());
        vo.setShipTime(order.getShipTime());
        vo.setDeliveryMode(order.getDeliveryMode());
        vo.setShippingCompany(order.getShippingCompany());
        vo.setTrackingNo(order.getTrackingNo());
        vo.setReceiverName(order.getReceiverName());
        vo.setReceiverPhone(order.getReceiverPhone());
        vo.setReceiverRegion(order.getReceiverRegion());
        vo.setReceiverDetail(order.getReceiverDetail());
        // 存量订单（v0.39 之前）没有收货信息：保持 null，前端据此不渲染该区块
        if (order.getReceiverName() != null) {
            vo.setReceiverFullAddress(String.join(" ",
                    nullToEmpty(order.getReceiverRegion()), nullToEmpty(order.getReceiverDetail())).trim());
        }
        vo.setPickupShopName(order.getPickupShopName());
        vo.setPickupAddress(order.getPickupAddress());
        vo.setPickupPhone(order.getPickupPhone());
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
