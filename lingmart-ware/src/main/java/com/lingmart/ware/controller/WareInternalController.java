package com.lingmart.ware.controller;

import com.lingmart.common.dto.StockLockRequest;
import com.lingmart.common.dto.StockSyncItem;
import com.lingmart.common.dto.WareStockInfo;
import com.lingmart.common.exception.BizException;
import com.lingmart.common.result.R;
import com.lingmart.ware.entity.StockFlowLog;
import com.lingmart.ware.service.WareService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 仓储内部接口（Feign 专用，不经过网关）。
 *
 * <p>锁定/扣减由订单服务在 Seata 全局事务内调用；库存同步由商品服务调用。</p>
 */
@RestController
@RequestMapping("/internal/ware")
@RequiredArgsConstructor
public class WareInternalController {

    private final WareService wareService;

    /**
     * 同步 SKU 库存（卖家发布/更新商品时调用）。
     */
    @PostMapping("/sku/sync")
    public R<Void> syncStock(@RequestBody List<StockSyncItem> items) {
        wareService.syncStock(items);
        return R.ok();
    }

    /**
     * 锁定库存（下单时调用，Seata 全局事务内）。
     */
    @PostMapping("/lock")
    public R<Void> lockStock(@RequestBody StockLockRequest request) {
        wareService.lockStock(request);
        return R.ok();
    }

    /**
     * 解锁库存（取消/超时取消时调用）。
     */
    @PostMapping("/unlock")
    public R<Void> unlockStock(@RequestBody StockLockRequest request) {
        wareService.unlockStock(request);
        return R.ok();
    }

    /**
     * 扣减库存（幂等一次成型）。正常支付链路已改由 MQ 事件消费（见 OrderPaidListener），
     * 本端点保留用于兼容/运维手动补偿触发。
     */
    @PostMapping("/deduct")
    public R<Void> deductStock(@RequestBody StockLockRequest request) {
        wareService.deductStock(request);
        return R.ok();
    }

    /**
     * 回补库存（退款通过后调用）。
     */
    @PostMapping("/restock")
    public R<Void> restockStock(@RequestBody StockLockRequest request) {
        wareService.restockStock(request);
        return R.ok();
    }

    /**
     * 查询 SKU 库存快照。
     */
    @GetMapping("/stock/{skuId}")
    public R<WareStockInfo> getStock(@PathVariable("skuId") Long skuId) {
        return R.ok(wareService.getStock(skuId));
    }

    /**
     * 批量查询 SKU 库存快照（商品服务展示「剩余件数」时调用）。
     *
     * @param skuIds SKU ID 列表
     * @return 库存信息列表
     */
    @PostMapping("/stock/batch")
    public R<List<WareStockInfo>> listStock(@RequestBody List<Long> skuIds) {
        return R.ok(wareService.listStock(skuIds));
    }

    /**
     * 人工介入清单：补偿失败(status=3)的流水，供运维处置（内部接口，运维经服务端口/内网调用）。
     */
    @GetMapping("/flow/human")
    public R<List<StockFlowLog>> humanFlows() {
        return R.ok(wareService.listHumanFlows());
    }

    /**
     * 人工处置流水：action=reset 将 status=3 重置为 0（待处理，重新进入定时补偿）；
     * action=done 将 status=3 标记为 1（已完成，人工确认已修复）。
     */
    @PostMapping("/flow/manual")
    public R<String> manualFlow(@RequestParam Long id, @RequestParam String action) {
        int rows = switch (action == null ? "" : action) {
            case "reset" -> wareService.resetFlowToPending(id);
            case "done" -> wareService.markFlowDone(id);
            default -> 0;
        };
        if (rows == 0) {
            throw new BizException("处置失败：流水不存在或状态已变化（仅 3=人工介入 可处置）");
        }
        return R.ok("处置成功");
    }
}
