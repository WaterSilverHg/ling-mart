package com.lingmart.ware.service.impl;

import com.lingmart.common.dto.StockLockItem;
import com.lingmart.common.dto.StockLockRequest;
import com.lingmart.common.dto.StockSyncItem;
import com.lingmart.common.dto.WareStockInfo;
import com.lingmart.common.exception.BizException;
import com.lingmart.ware.entity.WareOrderTask;
import com.lingmart.ware.entity.WareSku;
import com.lingmart.ware.mapper.StockFlowLogMapper;
import com.lingmart.ware.mapper.WareOrderTaskMapper;
import com.lingmart.ware.mapper.WareSkuMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 仓储服务单元测试（Mockito，不依赖数据库）。
 *
 * <p>覆盖库存同步、锁定/解锁/扣减的正常与异常分支：库存不足抛异常、
 * 解锁幂等（已解锁跳过）、解锁/扣减影响行数为 0 时抛异常不静默。</p>
 */
@ExtendWith(MockitoExtension.class)
class WareServiceImplTest {

    // 声明顺序必须与 WareServiceImpl 的字段声明顺序一致：被测类用 Lombok @RequiredArgsConstructor，
    // 参数顺序 = 字段声明顺序。新增依赖字段时必须同步补 @Mock 与构造参数（漏了会编译不过）。
    @Mock
    private WareSkuMapper wareSkuMapper;
    @Mock
    private WareOrderTaskMapper wareOrderTaskMapper;
    @Mock
    private StockFlowLogMapper stockFlowLogMapper;

    private WareServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new WareServiceImpl(wareSkuMapper, wareOrderTaskMapper, stockFlowLogMapper);
    }

    // ==================== 库存同步 ====================

    @Test
    void syncStock_shouldInsertNewSku() {
        when(wareSkuMapper.selectOne(any())).thenReturn(null);

        service.syncStock(List.of(syncItem(1001L, 5)));

        ArgumentCaptor<WareSku> captor = ArgumentCaptor.forClass(WareSku.class);
        verify(wareSkuMapper).insert(captor.capture());
        WareSku saved = captor.getValue();
        assertEquals(1001L, saved.getSkuId());
        assertEquals(5, saved.getStock());
        assertEquals(0, saved.getLockedStock());
    }

    @Test
    void syncStock_shouldUpdateExistingStock() {
        WareSku exist = new WareSku();
        exist.setId(1L);
        when(wareSkuMapper.selectOne(any())).thenReturn(exist);

        service.syncStock(List.of(syncItem(1001L, 9)));

        ArgumentCaptor<WareSku> captor = ArgumentCaptor.forClass(WareSku.class);
        verify(wareSkuMapper).updateById(captor.capture());
        assertEquals(9, captor.getValue().getStock());
    }

    @Test
    void syncStock_shouldIgnoreEmpty() {
        service.syncStock(null);
        service.syncStock(List.of());
        verify(wareSkuMapper, never()).insert(any(WareSku.class));
        verify(wareSkuMapper, never()).updateById(any(WareSku.class));
    }

    // ==================== 锁定 ====================

    @Test
    void lockStock_shouldLockAndInsertTask() {
        when(wareSkuMapper.tryLockStock(1001L, 1L, 2)).thenReturn(1);

        service.lockStock(request(1L, 1001L, 2));

        ArgumentCaptor<WareOrderTask> captor = ArgumentCaptor.forClass(WareOrderTask.class);
        verify(wareOrderTaskMapper).insert(captor.capture());
        assertEquals("SN001", captor.getValue().getOrderSn());
        assertEquals(0, captor.getValue().getStatus()); // 已锁定
    }

    @Test
    void lockStock_shouldThrowWhenInsufficient() {
        when(wareSkuMapper.tryLockStock(1001L, 1L, 99)).thenReturn(0);

        assertThrows(BizException.class, () -> service.lockStock(request(1L, 1001L, 99)));
        // 锁定失败不落任务
        verify(wareOrderTaskMapper, never()).insert(any(WareOrderTask.class));
    }

    // ==================== 解锁 ====================

    @Test
    void unlockStock_shouldSkipWhenAlreadyUnlocked() {
        // 任务已是「已解锁」：幂等跳过，不再解锁
        WareOrderTask task = new WareOrderTask();
        task.setStatus(2);
        when(wareOrderTaskMapper.selectOne(any())).thenReturn(task);

        service.unlockStock(request(1L, 1001L, 2));

        verify(wareSkuMapper, never()).unlockStock(any(), any(), any());
    }

    @Test
    void unlockStock_shouldThrowWhenRowsZero() {
        when(wareOrderTaskMapper.selectOne(any())).thenReturn(null);
        when(wareSkuMapper.unlockStock(1001L, 1L, 2)).thenReturn(0);

        assertThrows(BizException.class, () -> service.unlockStock(request(1L, 1001L, 2)));
    }

    @Test
    void unlockStock_shouldUnlockAndUpdateTask() {
        when(wareOrderTaskMapper.selectOne(any())).thenReturn(null);
        when(wareSkuMapper.unlockStock(1001L, 1L, 2)).thenReturn(1);

        service.unlockStock(request(1L, 1001L, 2));

        verify(wareSkuMapper).unlockStock(1001L, 1L, 2);
        verify(wareOrderTaskMapper).update(any(), any());
    }

    // ==================== 扣减 ====================

    @Test
    void deductStock_shouldDeductAndUpdateTask() {
        when(wareSkuMapper.deductStock(1001L, 1L, 2)).thenReturn(1);

        service.deductStock(request(1L, 1001L, 2));

        verify(wareSkuMapper).deductStock(1001L, 1L, 2);
        verify(wareOrderTaskMapper).update(any(), any());
    }

    @Test
    void deductStock_shouldThrowWhenRowsZero() {
        when(wareSkuMapper.deductStock(1001L, 1L, 2)).thenReturn(0);

        assertThrows(BizException.class, () -> service.deductStock(request(1L, 1001L, 2)));
    }

    // ==================== 退款回补 ====================

    @Test
    void restockStock_shouldRestockAndMarkRefunded() {
        // 任务已扣减（status=1）→ 回补可售库存 + 任务置已退款
        WareOrderTask task = new WareOrderTask();
        task.setStatus(1);
        when(wareOrderTaskMapper.selectOne(any())).thenReturn(task);
        when(wareSkuMapper.restockStock(1001L, 1L, 2)).thenReturn(1);

        service.restockStock(request(1L, 1001L, 2));

        verify(wareSkuMapper).restockStock(1001L, 1L, 2);
        verify(wareOrderTaskMapper).update(any(), any());
    }

    @Test
    void restockStock_shouldSkipWhenAlreadyRefunded() {
        // 任务已是「已退款回补」（status=3）：幂等跳过，不再加库存
        WareOrderTask task = new WareOrderTask();
        task.setStatus(3);
        when(wareOrderTaskMapper.selectOne(any())).thenReturn(task);

        service.restockStock(request(1L, 1001L, 2));

        verify(wareSkuMapper, never()).restockStock(any(), any(), any());
    }

    @Test
    void restockStock_shouldThrowWhenRowsZero() {
        WareOrderTask task = new WareOrderTask();
        task.setStatus(1);
        when(wareOrderTaskMapper.selectOne(any())).thenReturn(task);
        when(wareSkuMapper.restockStock(1001L, 1L, 2)).thenReturn(0);

        assertThrows(BizException.class, () -> service.restockStock(request(1L, 1001L, 2)));
    }

    // ==================== 库存查询 ====================

    @Test
    void getStock_shouldReturnNullWhenMissing() {
        when(wareSkuMapper.selectOne(any())).thenReturn(null);
        assertNull(service.getStock(1001L));
    }

    @Test
    void getStock_shouldReturnInfo() {
        WareSku sku = new WareSku();
        sku.setSkuId(1001L);
        sku.setStock(10);
        sku.setLockedStock(3);
        when(wareSkuMapper.selectOne(any())).thenReturn(sku);

        WareStockInfo info = service.getStock(1001L);

        assertNotNull(info);
        assertEquals(10, info.getStock());
        assertEquals(3, info.getLockedStock());
    }

    // ==================== 辅助 ====================

    private StockSyncItem syncItem(Long skuId, int stock) {
        StockSyncItem item = new StockSyncItem();
        item.setSkuId(skuId);
        item.setSpuId(100L);
        item.setShopId(1L);
        item.setTitle("测试SKU");
        item.setStock(stock);
        return item;
    }

    private StockLockRequest request(Long shopId, Long skuId, int num) {
        StockLockRequest request = new StockLockRequest();
        request.setOrderSn("SN001");
        StockLockItem item = new StockLockItem();
        item.setSkuId(skuId);
        item.setShopId(shopId);
        item.setNum(num);
        request.setItems(List.of(item));
        return request;
    }
}
