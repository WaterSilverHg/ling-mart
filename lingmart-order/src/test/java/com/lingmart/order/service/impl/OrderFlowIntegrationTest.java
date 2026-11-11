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
import com.lingmart.order.entity.UserAddress;
import com.lingmart.order.feign.ProductFeignClient;
import com.lingmart.order.feign.ShopFeignClient;
import com.lingmart.order.feign.WareFeignClient;
import com.lingmart.order.mapper.OrderItemMapper;
import com.lingmart.order.mapper.OrderMapper;
import com.lingmart.order.mapper.OrderPaidEventMapper;
import com.lingmart.order.service.AddressService;
import com.lingmart.ware.entity.WareOrderTask;
import com.lingmart.ware.entity.WareSku;
import com.lingmart.ware.mapper.StockFlowLogMapper;
import com.lingmart.ware.mapper.WareOrderTaskMapper;
import com.lingmart.ware.mapper.WareSkuMapper;
import com.lingmart.ware.service.impl.WareServiceImpl;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
    /**
     * 收货地址服务 mock。
     *
     * <p>本测试关注的是库存流转闭环，地址解析属于旁路；用固定返回值打桩即可，
     * 地址簿自身的 CRUD 由 {@code AddressServiceImplTest} 覆盖。</p>
     */
    @Mock
    private AddressService addressService;

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

    /**
     * 手工初始化 MyBatis-Plus 的 Lambda 缓存（{@code SFunction} → 列名）。
     *
     * <p>正常运行时该缓存由 {@link TableInfoHelper} 在实体随 Mapper 被扫描时填充；本测试是纯 Mockito、
     * 没有 Spring/MyBatis 上下文，不初始化的话内存 Mapper 桩里调用 {@code wrapper.getSqlSegment()}
     * 会抛 {@code MybatisPlusException: can not find lambda cache for this entity}。
     * 生产代码不受影响——它只在真正执行 SQL 时才解析 wrapper，那时缓存早已就绪。</p>
     */
    @BeforeAll
    static void initMybatisPlusLambdaCache() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), Order.class);
    }

    @BeforeEach
    void setUp() {
        setupWareMappers();
        wareService = new WareServiceImpl(wareSkuMapper, wareOrderTaskMapper, stockFlowLogMapper);
        wareFeignClient = stubWareFeignClient(wareService);
        setupOrderMappers();
        // 下单要解析收货地址（v0.39）：地址簿不是本测试的关注点，固定返回一条地址即可
        when(addressService.resolveForCheckout(any(), any())).thenReturn(address());

        orderService = new OrderServiceImpl(orderMapper, orderItemMapper, productFeignClient,
                wareFeignClient, shopFeignClient, rabbitTemplate, orderPaidEventMapper, objectMapper,
                addressService);
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
            // 模拟 DDL 的 NOT NULL DEFAULT 0：createOrder 只显式设置 orderStatus / payStatus，
            // refundStatus 靠列默认值补 0。真实库如此，内存桩也必须补——
            // 否则 requestRefund 的守卫（refund_status ∈ {0,3}）会因读到 null 而误判成未命中
            if (o.getRefundStatus() == null) {
                o.setRefundStatus(0);
            }
            if (o.getOrderStatus() == null) {
                o.setOrderStatus(0);
            }
            if (o.getPayStatus() == null) {
                o.setPayStatus(0);
            }
            orderStore.put(o.getId(), o);
            return 1;
        });
        when(orderMapper.selectOne(any())).thenAnswer(inv -> orderStore.get(1L));
        // 条件更新（状态守卫）：直接按生产代码构造的 wrapper 判断 WHERE 是否命中。
        // 不再像以前那样「看更新实体里有没有 refundStatus 来猜守卫列、并假定订单状态只可能从待支付出发」——
        // 那种写法在新增流转（ship 从已支付、confirm 从已发货、requestRefund 从 无退款/已拒绝）后会判错，
        // 而且错得安静：桩返回 0，测试只会看到「状态不允许」，看不出是桩的问题。
        when(orderMapper.update(any(Order.class), any())).thenAnswer(inv -> {
            Order u = inv.getArgument(0);
            Order stored = orderStore.get(u.getId());
            if (stored == null || !guardMatches(inv.getArgument(1), stored)) {
                return 0;
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

    /** 守卫列名：生产代码的条件更新统一形如「id = ? AND <order_status|refund_status> IN (…)」 */
    private static final Pattern GUARD_COLUMN =
            Pattern.compile("(order_status|refund_status)\\s+(?:=|IN)\\b");

    /**
     * 判断条件更新的守卫是否命中（在内存里模拟 SQL 的 WHERE 子句）。
     *
     * <p>读的是生产代码真实构造的 wrapper：列名取自 {@code getSqlSegment()}，
     * 期望值取自 {@code getParamNameValuePairs()}。参数表里 id 是 {@code Long}、状态值是
     * {@code Integer}，按类型即可把 id 排除，且不依赖 {@code HashMap} 的遍历顺序。</p>
     *
     * <p>形参类型必须是 {@link AbstractWrapper} 而不是某个具体子类：生产代码有的地方用
     * {@code LambdaQueryWrapper}、有的地方用 {@code LambdaUpdateWrapper}（后者才能把列置 null），
     * 收窄成子类会在 Mockito 取参时隐式强转而抛 {@code ClassCastException}。</p>
     *
     * <p><b>两行的顺序不能调换</b>：MyBatis-Plus 的参数表是<b>惰性填充</b>的
     * （{@code eq/in} 传入的是 {@code () -> formatParam(...)} 这样的延迟求值器），
     * 必须先调 {@code getSqlSegment()} 触发求值，{@code getParamNameValuePairs()} 才有内容。</p>
     */
    private static boolean guardMatches(AbstractWrapper<Order, ?, ?> wrapper, Order stored) {
        Matcher m = GUARD_COLUMN.matcher(wrapper.getSqlSegment());
        if (!m.find()) {
            return true;   // 无条件更新（当前生产代码不会走到这里）
        }
        Integer current = "order_status".equals(m.group(1))
                ? stored.getOrderStatus() : stored.getRefundStatus();
        return current != null && wrapper.getParamNameValuePairs().values().stream()
                .filter(Integer.class::isInstance)
                .anyMatch(v -> v.equals(current));
    }

    /**
     * 把 entity 的非 null 字段覆盖到 stored（**原地改写**，保证后续 selectOne 读到的还是同一实例）。
     *
     * <p>与真实 MyBatis-Plus 一致：{@code update(entity, wrapper)} 只把 entity 的非 null 字段写进 SET。
     * 所以这里漏补字段会造成「pay 明明写进了 payStatus、requestRefund 却读到 null」这类
     * **只在流程测试里才暴露**的假故障——纯 mock 的单测只 verify 调用，发现不了。</p>
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
        if (src.getRefundApplyTime() != null) {
            dst.setRefundApplyTime(src.getRefundApplyTime());
        }
        if (src.getCancelTime() != null) {
            dst.setCancelTime(src.getCancelTime());
        }
        // v0.27 发货信息：漏一个字段的后果是「发货后 selectOne 读不到物流信息」，
        // 而流程测试里表现为读到 null —— 不会被当成失败，只会让断言悄悄失真
        if (src.getDeliveryMode() != null) {
            dst.setDeliveryMode(src.getDeliveryMode());
        }
        if (src.getShippingCompany() != null) {
            dst.setShippingCompany(src.getShippingCompany());
        }
        if (src.getTrackingNo() != null) {
            dst.setTrackingNo(src.getTrackingNo());
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

    /** 下单时被快照的收货地址（v0.39） */
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
}
