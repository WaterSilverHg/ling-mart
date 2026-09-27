package com.lingmart.ware.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lingmart.common.dto.StockLockItem;
import com.lingmart.common.dto.StockLockRequest;
import com.lingmart.common.dto.StockSyncItem;
import com.lingmart.common.dto.WareStockInfo;
import com.lingmart.common.exception.BizException;
import com.lingmart.ware.entity.StockFlowLog;
import com.lingmart.ware.entity.WareOrderTask;
import com.lingmart.ware.entity.WareSku;
import com.lingmart.ware.mapper.StockFlowLogMapper;
import com.lingmart.ware.mapper.WareOrderTaskMapper;
import com.lingmart.ware.mapper.WareSkuMapper;
import com.lingmart.ware.service.WareService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 仓储服务实现。
 *
 * <p><b>事务边界约定</b>：本类的库存写方法全部标注 {@code @Transactional}。原因是 order 侧的
 * {@code createOrder/pay/cancelOrder/reviewRefund/cancelByTimeout} 都带 {@code @GlobalTransactional}，
 * 会经 Feign 调用本类方法作为 Seata AT 的**分支事务**。AT 要求一个分支内的多次写入处于**同一个本地事务**
 * 中：否则 autocommit 下每条语句各自成事务，「改 ware_sku + 写 ware_order_task + 插 stock_flow_log」
 * 将失去原子性，全局回滚语义也与「一个分支整体撤销」不一致。新增库存写方法时请一并补注解。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WareServiceImpl implements WareService {

    /** 库存锁定任务状态：已锁定 / 已扣减 / 已解锁 / 已退款回补 */
    private static final int TASK_LOCKED = 0;
    private static final int TASK_DEDUCTED = 1;
    private static final int TASK_UNLOCKED = 2;
    private static final int TASK_REFUNDED = 3;

    /** 库存流水状态：1=已完成（正常/幂等消费一次成型落库）；3=人工介入（补偿失败，见 StockFlowCompensationJob） */
    private static final int FLOW_DONE = 1;
    private static final int FLOW_HUMAN = 3;

    private final WareSkuMapper wareSkuMapper;
    private final WareOrderTaskMapper wareOrderTaskMapper;
    private final StockFlowLogMapper stockFlowLogMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void syncStock(List<StockSyncItem> items) {
        if (items == null || items.isEmpty()) {
            return;
        }
        for (StockSyncItem item : items) {
            // NULL 数量 = 不限量：置 unlimited 标记；stock 仅作占位（不限量时锁定/扣减等全部跳过）
            boolean unlimited = item.getStock() == null;
            WareSku exist = wareSkuMapper.selectOne(
                    new LambdaQueryWrapper<WareSku>().eq(WareSku::getSkuId, item.getSkuId()));
            if (exist == null) {
                WareSku sku = new WareSku();
                sku.setSpuId(item.getSpuId());
                sku.setSkuId(item.getSkuId());
                sku.setShopId(item.getShopId());
                sku.setTitle(item.getTitle());
                sku.setStock(unlimited ? 0 : item.getStock());
                sku.setLockedStock(0);
                sku.setUnlimited(unlimited ? 1 : 0);
                sku.setStatus(1);
                try {
                    wareSkuMapper.insert(sku);
                    continue;
                } catch (DuplicateKeyException e) {
                    // 并发建品/改库存：另一请求已插入同一 sku_id（uk_sku_id 兜底）。本方法「先查后插」在并发下
                    // 必然撞唯一键，捕获后转为更新，避免把框架异常冒成 500 到卖家界面（MySQL 下唯一键冲突不会
                    // 使当前事务失效，可继续执行）。
                    log.info("库存同步并发命中唯一键，转为更新：skuId={}", item.getSkuId());
                    exist = wareSkuMapper.selectOne(
                            new LambdaQueryWrapper<WareSku>().eq(WareSku::getSkuId, item.getSkuId()));
                    if (exist == null) {
                        throw new BizException("库存同步失败：skuId=" + item.getSkuId());
                    }
                }
            }
            WareSku update = new WareSku();
            update.setId(exist.getId());
            update.setTitle(item.getTitle());
            // 仅同步可售库存，锁定中数量保留；改为不限量时保留原数字占位（不影响实际判定）
            update.setStock(item.getStock() == null ? exist.getStock() : item.getStock());
            update.setUnlimited(unlimited ? 1 : 0);
            wareSkuMapper.updateById(update);
        }
        log.info("库存同步完成：items={}", items.size());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void lockStock(StockLockRequest request) {
        if (request.getItems() == null) {
            return;
        }
        for (StockLockItem item : request.getItems()) {
            // 不限量 SKU：不做锁定，但仍记录任务，便于后续 支付/取消/退款 状态流转一致
            if (isUnlimited(item.getSkuId())) {
                insertTask(request.getOrderSn(), item, TASK_LOCKED);
                continue;
            }
            int rows = wareSkuMapper.tryLockStock(item.getSkuId(), item.getShopId(), item.getNum());
            if (rows == 0) {
                throw new BizException("商品库存不足：skuId=" + item.getSkuId());
            }
            insertTask(request.getOrderSn(), item, TASK_LOCKED);
        }
        log.info("库存锁定成功：orderSn={}, skus={}", request.getOrderSn(), request.getItems().size());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void unlockStock(StockLockRequest request) {
        if (request.getItems() == null) {
            return;
        }
        for (StockLockItem item : request.getItems()) {
            // 幂等：锁定任务已是「已解锁」则跳过（超时取消与手动取消可能重复触发）
            WareOrderTask task = wareOrderTaskMapper.selectOne(new LambdaQueryWrapper<WareOrderTask>()
                    .eq(WareOrderTask::getOrderSn, request.getOrderSn())
                    .eq(WareOrderTask::getSkuId, item.getSkuId())
                    .eq(WareOrderTask::getShopId, item.getShopId())
                    .last("LIMIT 1"));
            if (task != null && TASK_UNLOCKED == task.getStatus()) {
                continue;
            }
            // 不限量 SKU：从未锁定过库存，直接置解锁态
            if (isUnlimited(item.getSkuId())) {
                updateTaskStatus(request.getOrderSn(), item, TASK_UNLOCKED);
                continue;
            }
            // 影响行数为 0 说明不存在可释放的锁定（无锁定/已释放），抛异常避免静默失败
            int rows = wareSkuMapper.unlockStock(item.getSkuId(), item.getShopId(), item.getNum());
            if (rows == 0) {
                throw new BizException("库存解锁失败：orderSn=" + request.getOrderSn() + ", skuId=" + item.getSkuId());
            }
            updateTaskStatus(request.getOrderSn(), item, TASK_UNLOCKED);
        }
        log.info("库存解锁完成：orderSn={}", request.getOrderSn());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deductStock(StockLockRequest request) {
        if (request.getItems() == null) {
            return;
        }
        for (StockLockItem item : request.getItems()) {
            // 幂等：该单该 SKU 已扣减过（Outbox 重发/重复消费）则跳过，避免重复扣
            if (isTaskDeducted(request.getOrderSn(), item)) {
                continue;
            }
            boolean unlimited = isUnlimited(item.getSkuId());
            if (!unlimited) {
                int rows = wareSkuMapper.deductStock(item.getSkuId(), item.getShopId(), item.getNum());
                if (rows == 0) {
                    // 影响 0 行：库存不足，或并发/重复时另一线程刚扣完——复核任务态确属“已扣减”则按重复处理
                    if (isTaskDeducted(request.getOrderSn(), item)) {
                        continue;
                    }
                    throw new BizException("库存扣减失败：skuId=" + item.getSkuId());
                }
            }
            // 成功（不限量=无数值扣减）：写一条“已完成”流水 + 任务置“已扣减”（同一事务，二者原子）。
            // 正常路径一次成型为 status=1，不再写 0 待回填；补偿 Job 只兜底异常/历史遗留的 status=0
            recordDoneFlow(request.getOrderSn(), item.getSkuId(), item.getNum());
            updateTaskStatus(request.getOrderSn(), item, TASK_DEDUCTED);
        }
        log.info("库存扣减完成：orderSn={}", request.getOrderSn());
    }

    /** 该单该 SKU 是否已处于“已扣减”态（幂等判断）。 */
    private boolean isTaskDeducted(String orderSn, StockLockItem item) {
        WareOrderTask task = wareOrderTaskMapper.selectOne(new LambdaQueryWrapper<WareOrderTask>()
                .eq(WareOrderTask::getOrderSn, orderSn)
                .eq(WareOrderTask::getSkuId, item.getSkuId())
                .eq(WareOrderTask::getShopId, item.getShopId())
                .last("LIMIT 1"));
        return task != null && TASK_DEDUCTED == task.getStatus();
    }

    /** 记录一条“已完成”扣减流水（change_num = -num）。 */
    private void recordDoneFlow(String orderSn, Long skuId, Integer num) {
        if (num == null) {
            return;
        }
        StockFlowLog flow = new StockFlowLog();
        flow.setOrderSn(orderSn);
        flow.setSkuId(skuId);
        flow.setChangeNum(-num);
        flow.setStatus(FLOW_DONE);
        flow.setVersion(1);
        stockFlowLogMapper.insert(flow);
    }

    @Override
    public List<StockFlowLog> listHumanFlows() {
        // 人工介入清单：status=3，最多返回 200 条（先人工先处理）
        return stockFlowLogMapper.selectList(new LambdaQueryWrapper<StockFlowLog>()
                .eq(StockFlowLog::getStatus, FLOW_HUMAN)
                .orderByDesc(StockFlowLog::getCreateTime)
                .last("LIMIT 200"));
    }

    @Override
    public int resetFlowToPending(Long id) {
        return id == null ? 0 : stockFlowLogMapper.resetToPending(id);
    }

    @Override
    public int markFlowDone(Long id) {
        return id == null ? 0 : stockFlowLogMapper.markDone(id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void restockStock(StockLockRequest request) {
        if (request.getItems() == null) {
            return;
        }
        for (StockLockItem item : request.getItems()) {
            WareOrderTask task = wareOrderTaskMapper.selectOne(new LambdaQueryWrapper<WareOrderTask>()
                    .eq(WareOrderTask::getOrderSn, request.getOrderSn())
                    .eq(WareOrderTask::getSkuId, item.getSkuId())
                    .eq(WareOrderTask::getShopId, item.getShopId())
                    .last("LIMIT 1"));
            // 幂等：任务已是「已退款回补」则跳过（退款审核可能重复触发）
            if (task != null && TASK_REFUNDED == task.getStatus()) {
                continue;
            }
            // 任务不存在或尚未扣减（支付未扣库存的异常态）：本次退款不加回库存，仅置退款态防重入
            if (task == null || TASK_DEDUCTED != task.getStatus()) {
                log.warn("退款回补跳过加库存（任务非已扣减）：orderSn={}, skuId={}", request.getOrderSn(), item.getSkuId());
                updateTaskStatus(request.getOrderSn(), item, TASK_REFUNDED);
                continue;
            }
            // 不限量 SKU：从未扣减真实库存，无库存可回补，仅置退款态防重入
            if (isUnlimited(item.getSkuId())) {
                updateTaskStatus(request.getOrderSn(), item, TASK_REFUNDED);
                continue;
            }
            // 正常路径：已扣减 → 加回可售库存，影响行数 0 说明 SKU 不存在，抛异常触发全局回滚
            int rows = wareSkuMapper.restockStock(item.getSkuId(), item.getShopId(), item.getNum());
            if (rows == 0) {
                throw new BizException("库存回补失败：skuId=" + item.getSkuId());
            }
            updateTaskStatus(request.getOrderSn(), item, TASK_REFUNDED);
        }
        log.info("库存回补完成：orderSn={}", request.getOrderSn());
    }

    @Override
    public WareStockInfo getStock(Long skuId) {
        WareSku sku = wareSkuMapper.selectOne(
                new LambdaQueryWrapper<WareSku>().eq(WareSku::getSkuId, skuId));
        if (sku == null) {
            return null;
        }
        WareStockInfo info = new WareStockInfo();
        info.setSkuId(sku.getSkuId());
        info.setStock(sku.getStock());
        info.setLockedStock(sku.getLockedStock());
        info.setUnlimited(sku.getUnlimited());
        return info;
    }

    @Override
    public List<WareStockInfo> listStock(List<Long> skuIds) {
        if (skuIds == null || skuIds.isEmpty()) {
            return List.of();
        }
        return wareSkuMapper.selectList(new LambdaQueryWrapper<WareSku>()
                        .in(WareSku::getSkuId, skuIds))
                .stream()
                .map(sku -> {
                    WareStockInfo info = new WareStockInfo();
                    info.setSkuId(sku.getSkuId());
                    info.setStock(sku.getStock());
                    info.setLockedStock(sku.getLockedStock());
                    info.setUnlimited(sku.getUnlimited());
                    return info;
                })
                .toList();
    }

    // ==================== 私有辅助方法 ====================

    /** 判断 SKU 是否为不限量（ware_sku 无记录时按限量处理，保持旧语义由锁定判定兜底） */
    private boolean isUnlimited(Long skuId) {
        WareSku sku = wareSkuMapper.selectOne(
                new LambdaQueryWrapper<WareSku>().eq(WareSku::getSkuId, skuId));
        return sku != null && Integer.valueOf(1).equals(sku.getUnlimited());
    }

    private void insertTask(String orderSn, StockLockItem item, int status) {
        WareOrderTask task = new WareOrderTask();
        task.setOrderSn(orderSn);
        task.setSkuId(item.getSkuId());
        task.setShopId(item.getShopId());
        task.setNum(item.getNum());
        task.setStatus(status);
        wareOrderTaskMapper.insert(task);
    }

    private void updateTaskStatus(String orderSn, StockLockItem item, int status) {
        WareOrderTask update = new WareOrderTask();
        update.setStatus(status);
        wareOrderTaskMapper.update(update, new LambdaQueryWrapper<WareOrderTask>()
                .eq(WareOrderTask::getOrderSn, orderSn)
                .eq(WareOrderTask::getSkuId, item.getSkuId())
                .eq(WareOrderTask::getShopId, item.getShopId()));
    }
}
