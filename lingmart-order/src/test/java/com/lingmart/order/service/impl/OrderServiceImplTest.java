package com.lingmart.order.service.impl;

import com.lingmart.common.dto.ShopInfo;
import com.lingmart.common.dto.SkuInfoDTO;
import com.lingmart.common.exception.BizException;
import com.lingmart.common.result.R;
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
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.cursor.Cursor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 订单服务单元测试（Mockito，不依赖数据库/中间件/Seata）。
 *
 * <p>覆盖下单幂等（重复幂等键返回已有订单、不自买）、支付/取消/确认/发货的状态机、
 * 超时取消兜底、流式导出。</p>
 */
@ExtendWith(MockitoExtension.class)
class OrderServiceImplTest {

    @Mock
    private OrderMapper orderMapper;
    @Mock
    private OrderItemMapper orderItemMapper;
    @Mock
    private ProductFeignClient productFeignClient;
    @Mock
    private WareFeignClient wareFeignClient;
    @Mock
    private ShopFeignClient shopFeignClient;
    @Mock
    private RabbitTemplate rabbitTemplate;
    @Mock
    private OrderPaidEventMapper orderPaidEventMapper;
    @Mock
    private ObjectMapper objectMapper;
    /** v0.39：下单要先解析收货地址，未打桩会 NPE */
    @Mock
    private AddressService addressService;

    @InjectMocks
    private OrderServiceImpl service;

    // ==================== 下单 ====================

    @Test
    void createOrder_shouldReturnExistingOnIdempotencyHit() {
        Order exist = order(1L, "SN_OLD", 0);
        when(orderMapper.selectOne(any())).thenReturn(exist);

        String orderSn = service.createOrder(1L, "dup-key", dto());

        assertEquals("SN_OLD", orderSn);
        verify(orderMapper, never()).insert(any(Order.class));
        verify(wareFeignClient, never()).lockStock(any());
    }

    @Test
    void createOrder_shouldSucceedAndLockStock() {
        when(shopFeignClient.getShopByUserId(1L)).thenReturn(R.ok(shop(999L)));
        when(productFeignClient.getSkuInfo(101L)).thenReturn(R.ok(sku(1L)));
        when(orderMapper.insert(any(Order.class))).thenAnswer(inv -> {
            Order o = inv.getArgument(0);
            o.setId(1L);
            return 1;
        });
        when(orderItemMapper.insert(any(OrderItem.class))).thenReturn(1);
        when(wareFeignClient.lockStock(any())).thenReturn(R.ok());
        when(addressService.resolveForCheckout(any(), any())).thenReturn(address());

        String orderSn = service.createOrder(1L, null, dto());

        assertNotNull(orderSn);
        verify(orderMapper).insert(any(Order.class));
        verify(wareFeignClient).lockStock(any());
    }

    @Test
    void createOrder_shouldRejectSelfBuy() {
        // 买家自己就是卖家（店铺 id 与 SKU 店铺一致）→ 禁止自买
        when(shopFeignClient.getShopByUserId(1L)).thenReturn(R.ok(shop(1L)));
        when(productFeignClient.getSkuInfo(101L)).thenReturn(R.ok(sku(1L)));

        assertThrows(BizException.class, () -> service.createOrder(1L, null, dto()));
        verify(orderMapper, never()).insert(any(Order.class));
    }

    // ==================== 支付 / 取消 / 确认 / 发货 ====================

    @Test
    void pay_shouldSucceedAndPublishOutbox() throws Exception {
        when(orderMapper.selectOne(any())).thenReturn(order(1L, "SN", 0));
        when(orderItemMapper.selectList(any())).thenReturn(List.of(item()));
        when(orderMapper.update(any(Order.class), any())).thenReturn(1);
        when(productFeignClient.updateSale(any())).thenReturn(R.ok());
        when(objectMapper.writeValueAsString(any())).thenReturn("{}");

        service.pay(1L, "SN");

        // 状态：带条件原子更新（不是无条件 updateById）；销量同步累加；库存扣减改为 Outbox 事件异步化
        verify(orderMapper).update(any(Order.class), any());
        verify(orderMapper, never()).updateById(any(Order.class));
        verify(productFeignClient).updateSale(any());
        // 库存在 ware 侧由 MQ 监听器异步扣减（order→ware 已无 deduct Feign 端点），故此处只验到 Outbox 落库
        verify(orderPaidEventMapper).insert(any(OrderPaidEvent.class));
    }

    @Test
    void pay_shouldRejectWhenStateUpdateMatchesNothing() {
        // 条件更新影响 0 行 = 该订单已不是「待支付」（重复支付/并发）→ 必须拒绝，且不累加销量
        when(orderMapper.selectOne(any())).thenReturn(order(1L, "SN", 1));
        when(orderMapper.update(any(Order.class), any())).thenReturn(0);

        assertThrows(BizException.class, () -> service.pay(1L, "SN"));
        verify(productFeignClient, never()).updateSale(any());
        verify(orderPaidEventMapper, never()).insert(any(OrderPaidEvent.class));
    }

    @Test
    void cancel_shouldUnlockStock() {
        when(orderMapper.selectOne(any())).thenReturn(order(1L, "SN", 0));
        when(orderItemMapper.selectList(any())).thenReturn(List.of(item()));
        when(orderMapper.update(any(Order.class), any())).thenReturn(1);
        when(wareFeignClient.unlockStock(any())).thenReturn(R.ok());

        service.cancel(1L, "SN");

        verify(orderMapper).update(any(Order.class), any());   // 原子守卫（不是无条件 updateById）
        verify(wareFeignClient).unlockStock(any());
    }

    @Test
    void cancel_shouldNotUnlockStockWhenGuardMisses() {
        // 并发：支付与取消同时到达。条件更新命中 0 行 = 状态已被支付改走 → 必须拒绝，
        // 且绝不能解锁库存（否则出现「订单已支付、库存却被释放」）
        when(orderMapper.selectOne(any())).thenReturn(order(1L, "SN", 0));
        when(orderMapper.update(any(Order.class), any())).thenReturn(0);

        assertThrows(BizException.class, () -> service.cancel(1L, "SN"));
        verify(wareFeignClient, never()).unlockStock(any());
    }

    @Test
    void confirm_shouldCompleteShippedOrder() {
        when(orderMapper.selectOne(any())).thenReturn(order(1L, "SN", 2));
        when(orderMapper.update(any(Order.class), any())).thenReturn(1);

        service.confirm(1L, "SN");

        verify(orderMapper).update(any(Order.class), any());
    }

    @Test
    void confirm_shouldRejectWhenGuardMisses() {
        when(orderMapper.selectOne(any())).thenReturn(order(1L, "SN", 2));
        when(orderMapper.update(any(Order.class), any())).thenReturn(0);

        assertThrows(BizException.class, () -> service.confirm(1L, "SN"));
    }

    // ==================== 发货超时自动确认收货（v0.39） ====================

    @Test
    void confirmBySystem_shouldCompleteShippedOrder() {
        when(orderMapper.selectOne(any())).thenReturn(order(1L, "SN", 2));
        when(orderMapper.update(any(Order.class), any())).thenReturn(1);

        service.confirmBySystem("SN");

        verify(orderMapper).update(any(Order.class), any());
    }

    @Test
    void confirmBySystem_shouldSkipWhenRefundInProgress() {
        Order shipped = order(1L, "SN", 2);
        shipped.setRefundStatus(1);
        when(orderMapper.selectOne(any())).thenReturn(shipped);

        service.confirmBySystem("SN");

        // 买家正在申请退款，自动确认收货必须让路
        verify(orderMapper, never()).update(any(Order.class), any());
    }

    @Test
    void confirmBySystem_shouldSilentlySkipWhenAlreadyCompleted() {
        when(orderMapper.selectOne(any())).thenReturn(order(1L, "SN", 2));
        // 买家恰好同时点过「确认收货」：守卫未命中，不应抛异常（定时任务里抛会把整批中断）
        when(orderMapper.update(any(Order.class), any())).thenReturn(0);

        assertDoesNotThrow(() -> service.confirmBySystem("SN"));
    }

    @Test
    void ship_shouldSucceedWithExpress() {
        when(shopFeignClient.getShopByUserId(1L)).thenReturn(R.ok(shop(7L)));
        Order o = order(1L, "SN", 1);
        o.setShopId(7L);
        when(orderMapper.selectOne(any())).thenReturn(o);
        when(orderMapper.update(any(Order.class), any())).thenReturn(1);

        service.ship(1L, "SN", shipDto(1, "顺丰速运", "SF123"));

        ArgumentCaptor<Order> captor = ArgumentCaptor.forClass(Order.class);
        verify(orderMapper).update(captor.capture(), any());
        assertEquals(2, captor.getValue().getOrderStatus());        // 已发货
        assertEquals(1, captor.getValue().getDeliveryMode());       // 快递
        assertEquals("顺丰速运", captor.getValue().getShippingCompany());
        assertEquals("SF123", captor.getValue().getTrackingNo());
    }

    @Test
    void ship_shouldSucceedWithoutLogistics() {
        when(shopFeignClient.getShopByUserId(1L)).thenReturn(R.ok(shop(7L)));
        Order o = order(1L, "SN", 1);
        o.setShopId(7L);
        when(orderMapper.selectOne(any())).thenReturn(o);
        when(orderMapper.update(any(Order.class), any())).thenReturn(1);

        service.ship(1L, "SN", shipDto(2, null, null));

        ArgumentCaptor<Order> captor = ArgumentCaptor.forClass(Order.class);
        verify(orderMapper).update(captor.capture(), any());
        assertEquals(2, captor.getValue().getDeliveryMode());
        assertNull(captor.getValue().getShippingCompany());
        assertNull(captor.getValue().getTrackingNo());
    }

    @Test
    void ship_shouldRejectExpressWithoutCompany() {
        assertThrows(BizException.class, () -> service.ship(1L, "SN", shipDto(1, null, "SF123")));
        assertThrows(BizException.class, () -> service.ship(1L, "SN", shipDto(1, "顺丰速运", "  ")));
        verify(orderMapper, never()).update(any(Order.class), any());
    }

    @Test
    void ship_shouldRejectNoLogisticsCarryingTrackingNo() {
        // 无需物流却传了单号 → 明确报错，而不是静默丢弃（静默会掩盖前端 bug）
        assertThrows(BizException.class, () -> service.ship(1L, "SN", shipDto(2, "顺丰速运", "SF123")));
        verify(orderMapper, never()).update(any(Order.class), any());
    }

    @Test
    void ship_shouldRejectUnknownDeliveryMode() {
        assertThrows(BizException.class, () -> service.ship(1L, "SN", shipDto(9, null, null)));
        assertThrows(BizException.class, () -> service.ship(1L, "SN", shipDto(null, null, null)));
        verify(orderMapper, never()).update(any(Order.class), any());
    }

    @Test
    void ship_shouldRejectWhenGuardMisses() {
        // 重复点击发货：条件更新 0 行 → 拒绝，不覆盖 ship_time
        when(shopFeignClient.getShopByUserId(1L)).thenReturn(R.ok(shop(7L)));
        Order o = order(1L, "SN", 1);
        o.setShopId(7L);
        when(orderMapper.selectOne(any())).thenReturn(o);
        when(orderMapper.update(any(Order.class), any())).thenReturn(0);

        assertThrows(BizException.class, () -> service.ship(1L, "SN", shipDto(1, "顺丰速运", "SF123")));
    }

    @Test
    void ship_shouldRejectForeignShop() {
        when(shopFeignClient.getShopByUserId(1L)).thenReturn(R.ok(shop(7L)));
        Order o = order(1L, "SN", 1);
        o.setShopId(8L);
        when(orderMapper.selectOne(any())).thenReturn(o);

        assertThrows(BizException.class, () -> service.ship(1L, "SN", shipDto(1, "顺丰速运", "SF123")));
        verify(orderMapper, never()).update(any(Order.class), any());
    }

    // ==================== 超时兜底 ====================

    @Test
    void cancelByTimeout_shouldCancelPendingOrder() {
        when(orderMapper.selectOne(any())).thenReturn(order(1L, "SN", 0));
        when(orderItemMapper.selectList(any())).thenReturn(List.of(item()));
        when(orderMapper.update(any(Order.class), any())).thenReturn(1);
        when(wareFeignClient.unlockStock(any())).thenReturn(R.ok());

        service.cancelByTimeout("SN");

        verify(wareFeignClient).unlockStock(any());
    }

    @Test
    void cancelByTimeout_shouldSkipNonPending() {
        when(orderMapper.selectOne(any())).thenReturn(order(1L, "SN", 1));
        when(orderMapper.update(any(Order.class), any())).thenReturn(0);   // 守卫未命中

        service.cancelByTimeout("SN");

        verify(wareFeignClient, never()).unlockStock(any());
    }

    @Test
    void cancelByTimeout_shouldNotThrowWhenGuardMisses() {
        // 兜底路径的关键语义：MQ 延迟消息与定时扫描会并发触发同一单，且订单可能刚被支付。
        // 守卫未命中必须静默跳过——抛异常会中断整轮超时扫描（scanTimeoutOrders 逐单 try/catch，
        // 但未捕获的异常会直接终止本次扫描循环）
        when(orderMapper.selectOne(any())).thenReturn(order(1L, "SN", 0));
        when(orderMapper.update(any(Order.class), any())).thenReturn(0);

        assertDoesNotThrow(() -> service.cancelByTimeout("SN"));
        verify(wareFeignClient, never()).unlockStock(any());
    }

    @Test
    void cancelByTimeout_shouldIgnoreMissingOrder() {
        when(orderMapper.selectOne(any())).thenReturn(null);

        assertDoesNotThrow(() -> service.cancelByTimeout("SN"));
        verify(orderMapper, never()).update(any(Order.class), any());
    }

    // ==================== 退款 ====================

    @Test
    void requestRefund_shouldSucceed() {
        Order o = order(1L, "SN", 1);
        o.setPayStatus(1);
        when(orderMapper.selectOne(any())).thenReturn(o);
        when(orderMapper.update(any(Order.class), any())).thenReturn(1);

        service.requestRefund(1L, "SN", "不想要了");

        ArgumentCaptor<Order> captor = ArgumentCaptor.forClass(Order.class);
        verify(orderMapper).update(captor.capture(), any());
        assertEquals(1, captor.getValue().getRefundStatus());
        // 申请时间必须落库：它是「卖家超时未处理则自动同意」的计时锚点，落不下就永远不会被自动处理
        assertNotNull(captor.getValue().getRefundApplyTime());
    }

    @Test
    void requestRefund_shouldRejectWhenGuardMisses() {
        // 并发双击「申请退款」：预检都通过，但守卫只让其中一个命中（refund_status ∈ {0,3} 仅一个能改）
        Order o = order(1L, "SN", 1);
        o.setPayStatus(1);
        when(orderMapper.selectOne(any())).thenReturn(o);
        when(orderMapper.update(any(Order.class), any())).thenReturn(0);

        assertThrows(BizException.class, () -> service.requestRefund(1L, "SN", "不想要了"));
    }

    @Test
    void requestRefund_shouldRejectUnpaid() {
        Order o = order(1L, "SN", 0);
        o.setPayStatus(0);
        when(orderMapper.selectOne(any())).thenReturn(o);

        assertThrows(BizException.class, () -> service.requestRefund(1L, "SN", "不想要了"));
    }

    @Test
    void requestRefund_shouldRejectWhenAlreadyRefunded() {
        Order o = order(1L, "SN", 5);
        o.setPayStatus(1);
        o.setRefundStatus(2);
        when(orderMapper.selectOne(any())).thenReturn(o);

        assertThrows(BizException.class, () -> service.requestRefund(1L, "SN", "不想要了"));
    }

    @Test
    void requestRefund_shouldRejectWhenPendingReview() {
        Order o = order(1L, "SN", 1);
        o.setPayStatus(1);
        o.setRefundStatus(1);
        when(orderMapper.selectOne(any())).thenReturn(o);

        assertThrows(BizException.class, () -> service.requestRefund(1L, "SN", "不想要了"));
    }

    @Test
    void requestRefund_shouldRejectWrongState() {
        Order o = order(1L, "SN", 0); // 待支付
        o.setPayStatus(1);
        when(orderMapper.selectOne(any())).thenReturn(o);

        assertThrows(BizException.class, () -> service.requestRefund(1L, "SN", "不想要了"));
    }

    @Test
    void requestRefund_shouldRequireReason() {
        Order o = order(1L, "SN", 1);
        o.setPayStatus(1);
        when(orderMapper.selectOne(any())).thenReturn(o);

        assertThrows(BizException.class, () -> service.requestRefund(1L, "SN", "  "));
    }

    @Test
    void reviewRefund_shouldApproveRestockAndRefund() {
        when(shopFeignClient.getShopByUserId(1L)).thenReturn(R.ok(shop(7L)));
        Order o = order(1L, "SN", 1);
        o.setShopId(7L);
        o.setRefundStatus(1);
        when(orderMapper.selectOne(any())).thenReturn(o);
        when(orderItemMapper.selectList(any())).thenReturn(List.of(item()));
        // 状态流转改为「带条件的原子更新」：返回影响行数 1 = 成功抢到本次审核
        when(orderMapper.update(any(), any())).thenReturn(1);
        when(wareFeignClient.restockStock(any())).thenReturn(R.ok());
        when(productFeignClient.rollbackSale(any(), any())).thenReturn(R.ok());

        service.reviewRefund(1L, "SN", true, null);

        verify(wareFeignClient).restockStock(any());
        verify(productFeignClient).rollbackSale(any(), any());
        ArgumentCaptor<Order> captor = ArgumentCaptor.forClass(Order.class);
        verify(orderMapper).update(captor.capture(), any());
        assertEquals(2, captor.getValue().getRefundStatus());
        assertEquals(5, captor.getValue().getOrderStatus()); // 已退款
    }

    @Test
    void reviewRefund_shouldRejectWhenAlreadyReviewed() {
        when(shopFeignClient.getShopByUserId(1L)).thenReturn(R.ok(shop(7L)));
        Order o = order(1L, "SN", 1);
        o.setShopId(7L);
        o.setRefundStatus(1);
        when(orderMapper.selectOne(any())).thenReturn(o);
        // 条件更新未命中（并发/重复点击时已被另一请求处理）→ 不得再回补库存与回退销量
        when(orderMapper.update(any(), any())).thenReturn(0);

        assertThrows(BizException.class, () -> service.reviewRefund(1L, "SN", true, null));
        verify(wareFeignClient, never()).restockStock(any());
        verify(productFeignClient, never()).rollbackSale(any(), any());
    }

    @Test
    void reviewRefund_shouldRejectWithReason() {
        when(shopFeignClient.getShopByUserId(1L)).thenReturn(R.ok(shop(7L)));
        Order o = order(1L, "SN", 1);
        o.setShopId(7L);
        o.setRefundStatus(1);
        when(orderMapper.selectOne(any())).thenReturn(o);
        when(orderMapper.update(any(Order.class), any())).thenReturn(1);

        service.reviewRefund(1L, "SN", false, "商品已使用");

        ArgumentCaptor<Order> captor = ArgumentCaptor.forClass(Order.class);
        verify(orderMapper).update(captor.capture(), any());
        assertEquals(3, captor.getValue().getRefundStatus());
        verify(wareFeignClient, never()).restockStock(any());
    }

    @Test
    void reviewRefund_shouldRejectWhenRejectGuardMisses() {
        // 卖家重复点击「拒绝」：守卫 0 行 → 拒绝。若这里放行，会出现
        // 「退款通过分支已回补库存、订单已置已退款，refund_status 却被覆盖成已拒绝」的矛盾状态
        when(shopFeignClient.getShopByUserId(1L)).thenReturn(R.ok(shop(7L)));
        Order o = order(1L, "SN", 1);
        o.setShopId(7L);
        o.setRefundStatus(1);
        when(orderMapper.selectOne(any())).thenReturn(o);
        when(orderMapper.update(any(Order.class), any())).thenReturn(0);

        assertThrows(BizException.class, () -> service.reviewRefund(1L, "SN", false, "商品已使用"));
        verify(wareFeignClient, never()).restockStock(any());
    }

    @Test
    void reviewRefund_shouldRequireRejectReason() {
        when(shopFeignClient.getShopByUserId(1L)).thenReturn(R.ok(shop(7L)));
        Order o = order(1L, "SN", 1);
        o.setShopId(7L);
        o.setRefundStatus(1);
        when(orderMapper.selectOne(any())).thenReturn(o);

        assertThrows(BizException.class, () -> service.reviewRefund(1L, "SN", false, ""));
    }

    @Test
    void reviewRefund_shouldRequirePendingRefund() {
        when(shopFeignClient.getShopByUserId(1L)).thenReturn(R.ok(shop(7L)));
        Order o = order(1L, "SN", 1);
        o.setShopId(7L);
        o.setRefundStatus(0); // 无退款申请
        when(orderMapper.selectOne(any())).thenReturn(o);

        assertThrows(BizException.class, () -> service.reviewRefund(1L, "SN", true, null));
    }

    @Test
    void reviewRefund_shouldForbidForeignShop() {
        when(shopFeignClient.getShopByUserId(1L)).thenReturn(R.ok(shop(8L)));
        Order o = order(1L, "SN", 1);
        o.setShopId(7L);
        o.setRefundStatus(1);
        when(orderMapper.selectOne(any())).thenReturn(o);

        assertThrows(BizException.class, () -> service.reviewRefund(1L, "SN", true, null));
        verify(wareFeignClient, never()).restockStock(any());
    }

    // ==================== 退款超时自动同意（v0.27） ====================

    @Test
    void autoApproveTimeoutRefund_shouldApproveAndRestock() {
        Order o = order(1L, "SN", 1);
        o.setShopId(7L);
        o.setRefundStatus(1);
        when(orderMapper.selectOne(any())).thenReturn(o);
        when(orderItemMapper.selectList(any())).thenReturn(List.of(item()));
        when(orderMapper.update(any(Order.class), any())).thenReturn(1);
        when(wareFeignClient.restockStock(any())).thenReturn(R.ok());
        when(productFeignClient.rollbackSale(any(), any())).thenReturn(R.ok());

        service.autoApproveTimeoutRefund("SN");

        // 与卖家手动通过走同一实现：订单置已退款 + 库存回补 + 销量回退
        ArgumentCaptor<Order> captor = ArgumentCaptor.forClass(Order.class);
        verify(orderMapper).update(captor.capture(), any());
        assertEquals(2, captor.getValue().getRefundStatus());
        assertEquals(5, captor.getValue().getOrderStatus());
        verify(wareFeignClient).restockStock(any());
        verify(productFeignClient).rollbackSale(any(), any());
    }

    @Test
    void autoApproveTimeoutRefund_shouldSkipWhenAlreadyHandled() {
        // 卖家已处理（状态非「退款中」）→ 静默跳过，不抛异常：
        // 本方法由定时扫描批量调用，抛异常会在正常竞态下刷错误日志
        Order o = order(1L, "SN", 1);
        o.setRefundStatus(2);
        when(orderMapper.selectOne(any())).thenReturn(o);

        assertDoesNotThrow(() -> service.autoApproveTimeoutRefund("SN"));
        verify(orderMapper, never()).update(any(Order.class), any());
        verify(wareFeignClient, never()).restockStock(any());
    }

    @Test
    void autoApproveTimeoutRefund_shouldSkipMissingOrder() {
        when(orderMapper.selectOne(any())).thenReturn(null);

        assertDoesNotThrow(() -> service.autoApproveTimeoutRefund("SN"));
        verify(orderMapper, never()).update(any(Order.class), any());
    }

    @Test
    void autoApproveTimeoutRefund_shouldSkipWhenGuardMisses() {
        // 预检通过但守卫未命中：卖家恰好在预检与写入之间处理了。属正常竞态，同样静默
        Order o = order(1L, "SN", 1);
        o.setRefundStatus(1);
        when(orderMapper.selectOne(any())).thenReturn(o);
        when(orderMapper.update(any(Order.class), any())).thenReturn(0);

        assertDoesNotThrow(() -> service.autoApproveTimeoutRefund("SN"));
        verify(wareFeignClient, never()).restockStock(any());
        verify(productFeignClient, never()).rollbackSale(any(), any());
    }

    // ==================== 流式导出 ====================

    @Test
    void streamSellerOrders_shouldStreamRowsWithStatusText() {
        when(shopFeignClient.getShopByUserId(1L)).thenReturn(R.ok(shop(7L)));
        OrderExportRow r1 = new OrderExportRow();
        r1.setOrderStatus(0);
        OrderExportRow r2 = new OrderExportRow();
        r2.setOrderStatus(3);
        Cursor<OrderExportRow> cursor = mock(Cursor.class);
        when(cursor.iterator()).thenReturn(List.of(r1, r2).iterator());
        when(orderMapper.selectSellerOrdersForExport(any(), any())).thenReturn(cursor);

        List<OrderExportRow> collected = new ArrayList<>();
        service.streamSellerOrders(1L, 30, collected::add);

        assertEquals(2, collected.size());
        assertEquals("待支付", collected.get(0).getStatusText());
        assertEquals("已完成", collected.get(1).getStatusText());
    }

    // ==================== 辅助 ====================

    /** 发货参数：mode 1=快递（需公司+单号） 2=无需物流（两者须为空） */
    private ShipDTO shipDto(Integer mode, String company, String tracking) {
        ShipDTO dto = new ShipDTO();
        dto.setDeliveryMode(mode);
        dto.setShippingCompany(company);
        dto.setTrackingNo(tracking);
        return dto;
    }

    private OrderCreateDTO dto() {
        OrderCreateDTO dto = new OrderCreateDTO();
        OrderItemDTO item = new OrderItemDTO();
        item.setSkuId(101L);
        item.setNum(2);
        dto.setItems(List.of(item));
        return dto;
    }

    private SkuInfoDTO sku(Long shopId) {
        SkuInfoDTO sku = new SkuInfoDTO();
        sku.setSpuId(10L);
        sku.setSkuId(101L);
        sku.setShopId(shopId);
        sku.setSpuTitle("测试商品");
        sku.setSkuTitle("黑色/128G");
        sku.setPrice(BigDecimal.valueOf(100));
        return sku;
    }

    private ShopInfo shop(Long id) {
        ShopInfo shop = new ShopInfo();
        shop.setId(id);
        shop.setShopName("测试店铺");
        shop.setStatus(1);
        return shop;
    }

    private Order order(Long id, String orderSn, int status) {
        Order order = new Order();
        order.setId(id);
        order.setOrderSn(orderSn);
        order.setBuyerId(1L);
        order.setShopId(7L);
        order.setOrderStatus(status);
        return order;
    }

    /** 下单时会被快照的收货地址（v0.39） */
    private UserAddress address() {
        UserAddress address = new UserAddress();
        address.setId(1L);
        address.setUserId(1L);
        address.setReceiverName("张三");
        address.setReceiverPhone("13900000001");
        address.setProvince("浙江省");
        address.setCity("杭州市");
        address.setDistrict("西湖区");
        address.setDetailAddress("文一西路 1 号");
        address.setIsDefault(1);
        return address;
    }

    private OrderItem item() {
        OrderItem item = new OrderItem();
        item.setOrderId(1L);
        item.setSpuId(10L);
        item.setSkuId(101L);
        item.setShopId(7L);
        item.setNum(2);
        return item;
    }
}
