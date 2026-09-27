package com.lingmart.ware.service;

import com.lingmart.common.dto.StockLockRequest;
import com.lingmart.common.dto.StockSyncItem;
import com.lingmart.common.dto.WareStockInfo;
import com.lingmart.ware.entity.StockFlowLog;

import java.util.List;

/**
 * 仓储服务业务接口。
 *
 * <p>锁定/扣减/解锁通常由订单服务在 Seata 全局事务内经 Feign 调用；
 * 库存同步由商品服务在发布/更新 SKU 时调用。</p>
 */
public interface WareService {

    /**
     * 同步 SKU 库存：按 skuId 存在则更新库存，不存在则新增（卖家发布/更新商品时调用）。
     *
     * @param items 库存条目列表
     */
    void syncStock(List<StockSyncItem> items);

    /**
     * 锁定库存：下单时调用（Seata 全局事务内，任一 SKU 不足抛异常触发回滚）。
     *
     * @param request 锁定请求（订单号 + SKU 条目）
     */
    void lockStock(StockLockRequest request);

    /**
     * 解锁库存：取消/超时取消订单时调用。
     *
     * @param request 解锁请求
     */
    void unlockStock(StockLockRequest request);

    /**
     * 扣减库存（幂等）：消费「订单支付成功」MQ 事件时调用（锁定占用转为真实售出）。
     * 同一 (order_sn, sku) 已扣减过则跳过（防 Outbox 重发/重复消费）；成功后流水直接置“已完成”。
     *
     * @param request 扣减请求
     */
    void deductStock(StockLockRequest request);

    /**
     * 回补库存：退款通过后调用（已售出商品重新加回可售库存），幂等（任务已是已退款则跳过）。
     *
     * @param request 回补请求
     */
    void restockStock(StockLockRequest request);

    /**
     * 查询某个 SKU 的库存快照。
     *
     * @param skuId SKU ID
     * @return 库存信息；不存在返回 null
     */
    WareStockInfo getStock(Long skuId);

    /**
     * 批量查询 SKU 库存快照（商品服务展示「剩余件数」时调用，内部接口）。
     *
     * @param skuIds SKU ID 列表
     * @return 库存信息列表；不在 {@code ware_sku} 中的 SKU 不返回
     */
    List<WareStockInfo> listStock(List<Long> skuIds);

    /**
     * 人工介入清单：查询 status=3（补偿失败）的流水，供运维处置。
     *
     * @return 待人工处置的流水（按创建时间倒序，上限 200）
     */
    List<StockFlowLog> listHumanFlows();

    /**
     * 人工处置：将 status=3 的流水重置为 0（待处理），让定时任务重新尝试补偿。
     *
     * @param id 流水主键
     * @return 影响行数（0=状态已变化，如已被处置）
     */
    int resetFlowToPending(Long id);

    /**
     * 人工处置：将 status=3 的流水标记为 1（已完成），用于运维人工确认数据已修复。
     *
     * @param id 流水主键
     * @return 影响行数（0=状态已变化）
     */
    int markFlowDone(Long id);
}
