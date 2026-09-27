package com.lingmart.order.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lingmart.common.dto.ShopInfo;
import com.lingmart.common.dto.SkuInfoDTO;
import com.lingmart.common.dto.StockLockItem;
import com.lingmart.common.dto.StockLockRequest;
import com.lingmart.common.dto.StockSyncItem;
import com.lingmart.common.dto.WareStockInfo;
import com.lingmart.common.result.R;
import com.lingmart.order.dto.OrderCreateDTO;
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
import com.lingmart.ware.entity.WareOrderTask;
import com.lingmart.ware.entity.WareSku;
import com.lingmart.ware.mapper.StockFlowLogMapper;
import com.lingmart.ware.mapper.WareOrderTaskMapper;
import com.lingmart.ware.mapper.WareSkuMapper;
import com.lingmart.ware.service.impl.WareServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 订单端到端集成测试（服务级 Seata 流程模拟）。
 *
 * <p>将 {@link OrderServiceImpl} 与真实的 {@link WareServiceImpl} 通过 Feign mock 打通：
 * 建单 → 锁库存（Seata 分支）→ 支付 → 扣减；另一场景建单 → 取消 → 解锁。
 * Mapper 用内存 Map 模拟持久化（不含真实数据库/Seata TC/中间件），
 * 用于回归「下单/支付/取消的库存流转闭环」。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OrderFlowIntegrationTest {

    // 订单侧 mock
    @Mock
    private OrderMapper orderMapper;
    @Mock
    private OrderItemMapper orderItemMapper;
    @Mock
    private ProductFeignClient productFeignClient;
    @Mock
    private ShopFeignClient shopFeignClient;
    @Mock
    private RabbitTemplate rabbitTemplate;
    @Mock
    private OrderPaidEventMapper orderPaidEventMapper;
    @Mock
    private ObjectMapper objectMapper;

    // 仓储侧 mock（喂给真实的 WareServiceImpl）
    // 声明顺序必须与 WareServiceImpl 的字段声明顺序一致（Lombok @RequiredArgsConstructor 参数顺序 = 字段声明顺序）
    @Mock
    private WareSkuMapper wareSkuMapper;
    @Mock
    private WareOrderTaskMapper wareOrderTaskMapper;
    @Mock
    private StockFlowLogMapper stockFlowLogMapper;

    // 仓储真实实现：库存流转的真实业务逻辑
    private WareServiceImpl wareService;
    // 仓储 Feign 客户端 mock：委托给真实 WareServiceImpl，模拟 Seata 分支调用
    private WareFeignClient wareFeignClient;

    // 内存持久化
    private final Map<Long, WareSku> skuStore = new HashMap<>();
    private final Map<Long, WareOrderTask> taskStore = new HashMap<>();
    private final Map<Long, Order> orderStore = new HashMap<>();
    private final Map<Long, List<OrderItem>> orderItemsStore = new HashMap<>();
    private final AtomicLong idSeq = new AtomicLong(1);

    private OrderServiceImpl orderService;

    @BeforeEach
    void setUp() {
        setupWareMappers();
        wareService = new WareServiceImpl(wareSkuMapper, wareOrderTaskMapper, stockFlowLogMapper);
        wareFeignClient = stubWareFeignClient(wareService);
        setupOrderMappers();

        orderService = new OrderServiceImpl(orderMapper, orderItemMapper, productFeignClient,
                wareFeignClient, shopFeignClient, rabbitTemplate, orderPaidEventMapper, objectMapper);
        ReflectionTestUtils.setField(orderService, "timeoutMs", 120_000L);
    }

    /** 完整链路一：建单 → 锁库存 → 支付 → 扣减（可售与锁定同步减少） */
    @Test
    void fullFlow_createPay_shouldDeductStock() {
        // 0. 仓储库存就绪
        wareService.syncStock(List.of(syncItem(101L, 10)));

        // 1. 建单（买家 99 非卖家店铺 1，不自买）
        when(shopFeignClient.getShopByUserId(99L)).thenReturn(R.ok(shop(999L)));
        when(productFeignClient.getSkuInfo(101L)).thenReturn(R.ok(sku(1L)));
        String orderSn = orderService.createOrder(99L, null, dto());

        assertNotNull(orderSn);
        // 2. 锁定生效：locked_stock = 2
        assertEquals(2, wareService.getStock(101L).getLockedStock());

        // 3. 支付：订单置已付 + 写「支付成功」Outbox（库存扣减改为异步，此时库存尚未变化）
        when(productFeignClient.updateSale(any())).thenReturn(R.ok());
        orderService.pay(99L, orderSn);

        verify(orderPaidEventMapper).insert(any(OrderPaidEvent.class));
        assertEquals(10, wareService.getStock(101L).getStock(), "扣除库存是异步的，此刻还未扣");
        assertEquals(2, wareService.getStock(101L).getLockedStock());

        // 4. 模拟 ware 消费支付成功事件 → 扣减：stock 10→8，locked 2→0
        simulateWareConsume(orderSn);

        WareStockInfo after = wareService.getStock(101L);
        assertEquals(8, after.getStock());
        assertEquals(0, after.getLockedStock());
    }

    /** 完整链路二：建单 → 锁库存 → 取消 → 解锁（锁定占用释放） */
    @Test
    void fullFlow_createCancel_shouldUnlockStock() {
        wareService.syncStock(List.of(syncItem(101L, 10)));

        when(shopFeignClient.getShopByUserId(99L)).thenReturn(R.ok(shop(999L)));
        when(productFeignClient.getSkuInfo(101L)).thenReturn(R.ok(sku(1L)));
        String orderSn = orderService.createOrder(99L, null, dto());

        assertEquals(2, wareService.getStock(101L).getLockedStock());

        orderService.cancel(99L, orderSn);

        WareStockInfo after = wareService.getStock(101L);
        assertEquals(10, after.getStock());
        assertEquals(0, after.getLockedStock());
    }

    /** 完整链路三：建单 → 锁库存 → 支付扣减 → 申请退款 → 卖家同意 → 库存回补 */
    @Test
    void fullFlow_refund_shouldRestockStock() {
        wareService.syncStock(List.of(syncItem(101L, 10)));

        when(shopFeignClient.getShopByUserId(99L)).thenReturn(R.ok(shop(999L)));
        when(productFeignClient.getSkuInfo(101L)).thenReturn(R.ok(sku(1L)));
        String orderSn = orderService.createOrder(99L, null, dto());
        when(productFeignClient.updateSale(any())).thenReturn(R.ok());
        orderService.pay(99L, orderSn);
        simulateWareConsume(orderSn); // 支付后由 MQ 异步扣减（本测试手工触发）

        assertEquals(8, wareService.getStock(101L).getStock());

        // 买家申请退款
        orderService.requestRefund(99L, orderSn, "不想要了");

        // 卖家（店铺 shopId=1）审核通过：订单置已退款 + 库存回补 8→10 + 销量回退
        when(shopFeignClient.getShopByUserId(1L)).thenReturn(R.ok(shop(1L)));
        when(productFeignClient.rollbackSale(any(), any())).thenReturn(R.ok());
        orderService.reviewRefund(1L, orderSn, true, null);

        WareStockInfo after = wareService.getStock(101L);
        assertEquals(10, after.getStock(), "退款后库存应回补");
        assertEquals(0, after.getLockedStock());
    }

    // ==================== 内存仓储 Mapper（模拟原子 SQL 语义）====================

    private void setupWareMappers() {
        // selectOne(WareSku)：按 skuId 查询
        when(wareSkuMapper.selectOne(any())).thenAnswer(inv -> skuStore.get(101L));
        // tryLockStock：仅当 可售 - 锁定 >= num 时锁定成功
        when(wareSkuMapper.tryLockStock(any(), any(), any())).thenAnswer(inv -> {
            int num = inv.getArgument(2);
            WareSku s = skuStore.get(101L);
            if (s == null || s.getStock() - s.getLockedStock() < num) {
                return 0;
            }
            s.setLockedStock(s.getLockedStock() + num);
            return 1;
        });
        // unlockStock：锁定 >= num 才可释放
        when(wareSkuMapper.unlockStock(any(), any(), any())).thenAnswer(inv -> {
            int num = inv.getArgument(2);
            WareSku s = skuStore.get(101L);
            if (s == null || s.getLockedStock() < num) {
                return 0;
            }
            s.setLockedStock(s.getLockedStock() - num);
            return 1;
        });
        // deductStock：锁定 >= num 时锁定与可售各减
        when(wareSkuMapper.deductStock(any(), any(), any())).thenAnswer(inv -> {
            int num = inv.getArgument(2);
            WareSku s = skuStore.get(101L);
            if (s == null || s.getLockedStock() < num) {
                return 0;
            }
            s.setLockedStock(s.getLockedStock() - num);
            s.setStock(s.getStock() - num);
            return 1;
        });
        // restockStock：退款回补，可售 + num
        when(wareSkuMapper.restockStock(any(), any(), any())).thenAnswer(inv -> {
            int num = inv.getArgument(2);
            WareSku s = skuStore.get(101L);
            if (s == null) {
                return 0;
            }
            s.setStock(s.getStock() + num);
            return 1;
        });
        // insert(WareSku)：落内存
        when(wareSkuMapper.insert(any(WareSku.class))).thenAnswer(inv -> {
            WareSku s = inv.getArgument(0);
            skuStore.put(s.getSkuId(), s);
            return 1;
        });
        // updateById(WareSku)：合并更新（仅覆盖非空字段）
        when(wareSkuMapper.updateById(any(WareSku.class))).thenAnswer(inv -> {
            WareSku u = inv.getArgument(0);
            WareSku stored = skuStore.get(u.getSkuId());
            if (stored != null) {
                if (u.getTitle() != null) {
                    stored.setTitle(u.getTitle());
                }
                if (u.getStock() != null) {
                    stored.setStock(u.getStock());
                }
            }
            return 1;
        });
        // 锁定任务：selectOne 返回唯一任务（单订单场景，按真实 orderSn 落库）
        when(wareOrderTaskMapper.selectOne(any())).thenAnswer(inv ->
                taskStore.values().stream().findFirst().orElse(null));
        when(wareOrderTaskMapper.insert(any(WareOrderTask.class))).thenAnswer(inv -> {
            WareOrderTask t = inv.getArgument(0);
            t.setId(idSeq.getAndIncrement());
            taskStore.put(t.getId(), t);
            return 1;
        });
        when(wareOrderTaskMapper.update(any(WareOrderTask.class), any())).thenAnswer(inv -> {
            WareOrderTask u = inv.getArgument(0);
            taskStore.values().forEach(t -> t.setStatus(u.getStatus()));
            return 1;
        });
    }

    /** 仓储 Feign mock：把 lock/unlock/restock 委托给真实 WareServiceImpl（deduct 已无 Feign 端点，走 MQ 异步） */
    private WareFeignClient stubWareFeignClient(WareServiceImpl real) {
        WareFeignClient feign = org.mockito.Mockito.mock(WareFeignClient.class);
        when(feign.lockStock(any())).thenAnswer(inv -> {
            real.lockStock(inv.getArgument(0));
            return R.ok();
        });
        when(feign.unlockStock(any())).thenAnswer(inv -> {
            real.unlockStock(inv.getArgument(0));
            return R.ok();
        });
        when(feign.restockStock(any())).thenAnswer(inv -> {
            real.restockStock(inv.getArgument(0));
            return R.ok();
        });
        return feign;
    }

    // ==================== 内存订单 Mapper ====================

    private void setupOrderMappers() {
        // 建单：落内存（本测试单订单场景，selectOne 返回唯一订单即可）
        when(orderMapper.insert(any(Order.class))).thenAnswer(inv -> {
            Order o = inv.getArgument(0);
            o.setId(1L);
            orderStore.put(o.getId(), o);
            return 1;
        });
        when(orderMapper.selectOne(any())).thenAnswer(inv -> orderStore.get(1L));
        when(orderMapper.updateById(any(Order.class))).thenAnswer(inv -> {
            Order u = inv.getArgument(0);
            Order stored = orderStore.get(u.getId());
            if (stored != null) {
                applyNonNullFields(u, stored);
            }
            return 1;
        });
        // 条件更新（状态守卫）：pay 要求仍为「待支付(0)」、退款审核要求仍为「待审核(1)」；
        // 条件不满足返回 0（模拟 WHERE 未命中），满足则应用字段变更并返回 1
        when(orderMapper.update(any(Order.class), any())).thenAnswer(inv -> {
            Order u = inv.getArgument(0);
            Order stored = orderStore.get(u.getId());
            if (stored == null) {
                return 0;
            }
            if (u.getRefundStatus() != null) {
                Integer current = stored.getRefundStatus();
                if (current == null || current != 1) {
                    return 0;
                }
            } else {
                Integer current = stored.getOrderStatus();
                if (current == null || current != 0) {
                    return 0;
                }
            }
            applyNonNullFields(u, stored);
            return 1;
        });
        // 明细：insert 收集、selectList 按订单返回
        when(orderItemMapper.insert(any(OrderItem.class))).thenAnswer(inv -> {
            OrderItem it = inv.getArgument(0);
            it.setId(idSeq.getAndIncrement());
            orderItemsStore.computeIfAbsent(it.getOrderId(), k -> new ArrayList<>()).add(it);
            return 1;
        });
        when(orderItemMapper.selectList(any())).thenAnswer(inv ->
                orderItemsStore.getOrDefault(1L, List.of()));
    }

    /**
     * 把 entity 的非 null 字段覆盖到 stored（**原地改写**，保证后续 selectOne 读到的还是同一实例）。
     *
     * <p>两个内存 Mapper 桩（{@code updateById} 与条件 {@code update(entity, wrapper)}）**必须共用这一份逻辑**：
     * 真实 MyBatis-Plus 里两者对字段的语义相同（entity 的非 null 字段 → 命中的行），一旦只给其中一个补字段，
     * 就会出现「pay 明明写进了 payStatus，requestRefund 却读到 null」这类**只在流程测试里才暴露**的假故障
     * —— 单测走的是纯 mock（只 verify 调用），不会发现两个桩不一致。</p>
     */
    private static void applyNonNullFields(Order src, Order dst) {
        if (src.getOrderStatus() != null) {
            dst.setOrderStatus(src.getOrderStatus());
        }
        if (src.getPayStatus() != null) {
            dst.setPayStatus(src.getPayStatus());
        }
        if (src.getPayTime() != null) {
            dst.setPayTime(src.getPayTime());
        }
        if (src.getRefundStatus() != null) {
            dst.setRefundStatus(src.getRefundStatus());
        }
        if (src.getRefundReason() != null) {
            dst.setRefundReason(src.getRefundReason());
        }
        if (src.getRefundRejectReason() != null) {
            dst.setRefundRejectReason(src.getRefundRejectReason());
        }
        if (src.getRefundTime() != null) {
            dst.setRefundTime(src.getRefundTime());
        }
        if (src.getCancelTime() != null) {
            dst.setCancelTime(src.getCancelTime());
        }
    }

    // ==================== 辅助 ====================

    /**
     * 模拟「ware 消费支付成功事件」这一步。
     *
     * <p>v0.6 起支付不再同步扣库存：`pay()` 只把「支付成功事件」写进 Outbox（同一本地事务），
     * 由 `OrderPaidOutboxRelay` 周期投递到 MQ、ware 的监听器幂等扣减。本测试不引入 MQ，
     * 因此直接调用 ware 侧监听器最终会调到的那一层（`WareService.deductStock`），
     * 继续验证库存流转结果；order→ware 之间不再有 deduct 的 Feign 端点（只有 lock/unlock/restock）。</p>
     */
    private void simulateWareConsume(String orderSn) {
        StockLockRequest request = new StockLockRequest();
        request.setOrderSn(orderSn);
        StockLockItem item = new StockLockItem();
        item.setSkuId(101L);
        item.setShopId(1L);
        item.setNum(2);
        request.setItems(List.of(item));
        wareService.deductStock(request);
    }

    private StockSyncItem syncItem(Long skuId, int stock) {
        StockSyncItem item = new StockSyncItem();
        item.setSkuId(skuId);
        item.setSpuId(10L);
        item.setShopId(1L);
        item.setTitle("测试SKU");
        item.setStock(stock);
        return item;
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

    private OrderCreateDTO dto() {
        OrderCreateDTO dto = new OrderCreateDTO();
        OrderItemDTO item = new OrderItemDTO();
        item.setSkuId(101L);
        item.setNum(2);
        dto.setItems(List.of(item));
        return dto;
    }
}
