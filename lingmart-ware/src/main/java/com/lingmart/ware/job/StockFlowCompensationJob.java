package com.lingmart.ware.job;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lingmart.ware.entity.StockFlowLog;
import com.lingmart.ware.entity.WareOrderTask;
import com.lingmart.ware.entity.WareSku;
import com.lingmart.ware.mapper.StockFlowLogMapper;
import com.lingmart.ware.mapper.WareOrderTaskMapper;
import com.lingmart.ware.mapper.WareSkuMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 库存扣减流水「定时补偿」任务（对账兜底）。
 *
 * <p>原则：
 * <ul>
 *   <li>只补不删：对悬空(status=0)超过阈值的扣减流水<b>重放扣减</b>（把锁定转真实售出），
 *       绝不执行“加回/回滚”——回滚/解锁只能由取消订单、退款等业务主动发起；</li>
 *   <li>基于流水：不比对库存数值，只看流水状态机；</li>
 *   <li>幂等：先用「status: 0→2」条件更新(CAS)抢占，抢占失败的流水直接跳过，保证同一笔只补偿一次；</li>
 *   <li>人工红线：重放失败（库存不足/SKU 不存在/异常）置为 status=3 并告警，绝不静默跳过或无限重试；</li>
 *   <li>卡死回收：抢占后进程中断会留下 2(补偿中) 的孤儿流水——本任务只扫 0，该流水既不重试也不告警，
 *       且其 {@code ware_order_task} 未置「已扣减」会导致<b>退款回补被跳过</b>。故新增「2 且超过阈值」
 *       的回收分支：一律置 3 人工介入并告警，<b>绝不自动重放</b>（无法区分「扣减已发生」与「尚未扣减」，
 *       重放可能超卖，与本模块「宁告警不超卖」一致）。</li>
 * </ul>
 *
 * <p>重放语义与支付扣减一致：仅当 {@code locked_stock >= num}（该单锁定还在，说明扣减确实没发生过）
 * 才会成功；若锁定已被原扣减消耗则影响行数为 0 → 落入人工介入并告警（宁告警不超卖）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StockFlowCompensationJob {

    /** 流水状态：待处理 */
    private static final int FLOW_PENDING = 0;
    /** 流水状态：补偿中（CAS 抢占后的中间态） */
    private static final int FLOW_COMPENSATING = 2;
    /** 库存锁定任务状态：已扣减 */
    private static final int TASK_DEDUCTED = 1;

    /** 待处理超过该阈值（单次扫描）视为系统性异常，额外告警一次 */
    private static final int SYSTEMATIC_ALERT_THRESHOLD = 100;
    /** 单次扫描上限，防止任务堆积拖垮数据库 */
    private static final int SCAN_LIMIT = 1000;
    /** 「补偿中」超过该分钟数视为进程中断遗留（抢占后崩溃，既未完成也未置人工），回收为人工介入 */
    private static final int STUCK_COMPENSATING_MINUTES = 15;

    private final StockFlowLogMapper stockFlowLogMapper;
    private final WareSkuMapper wareSkuMapper;
    private final WareOrderTaskMapper wareOrderTaskMapper;

    /**
     * 每 5 分钟执行一次：先回收卡死的「补偿中」流水，再扫描「待处理超过 10 分钟」的扣减流水并重放扣减。
     */
    @Scheduled(cron = "0 */5 * * * ?")
    public void compensatePendingFlows() {
        recoverStuckCompensating();
        // 预留 10 分钟给正常「扣减→订单侧回填」链路；串行逐条处理，天然避免同 SKU 并发补偿
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(10);
        List<StockFlowLog> pending = stockFlowLogMapper.selectList(new LambdaQueryWrapper<StockFlowLog>()
                .eq(StockFlowLog::getStatus, FLOW_PENDING)
                .lt(StockFlowLog::getCreateTime, cutoff)
                .orderByAsc(StockFlowLog::getCreateTime)
                .last("LIMIT " + SCAN_LIMIT));
        if (pending.isEmpty()) {
            return;
        }
        if (pending.size() > SYSTEMATIC_ALERT_THRESHOLD) {
            log.error("【STOCK_FLOW_ALERT】单次扫描待补偿流水 {} 条（>{}），疑似系统性故障，请立即排查下游扣减链路",
                    pending.size(), SYSTEMATIC_ALERT_THRESHOLD);
        }
        log.info("库存流水补偿扫描：待处理 {} 条", pending.size());
        for (StockFlowLog flow : pending) {
            compensateOne(flow);
        }
    }

    /**
     * 回收卡死的「补偿中」流水：抢占（0→2）后进程中断会永久停留在 2——本任务只扫 0，它既不重试也不告警，
     * 且 {@code ware_order_task} 未置「已扣减」会让退款回补被跳过（买家退款成功但库存不回补）。
     *
     * <p>处理方式：一律置 3（人工介入）并告警，<b>不自动重放</b>。原因是无法从流水本身区分
     * 「库存已扣、任务未推进」与「尚未扣减」，重放可能造成超卖，与本模块「宁告警不超卖」一致。
     * 由运维用 {@code POST /internal/ware/flow/manual?id=&action=reset|done} 处置。</p>
     */
    private void recoverStuckCompensating() {
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(STUCK_COMPENSATING_MINUTES);
        List<StockFlowLog> stuck = stockFlowLogMapper.selectList(new LambdaQueryWrapper<StockFlowLog>()
                .eq(StockFlowLog::getStatus, FLOW_COMPENSATING)
                .lt(StockFlowLog::getUpdateTime, cutoff)
                .orderByAsc(StockFlowLog::getUpdateTime)
                .last("LIMIT " + SCAN_LIMIT));
        for (StockFlowLog flow : stuck) {
            // CAS 2→3：影响 0 行说明已被其它实例回收或状态已变，跳过
            if (stockFlowLogMapper.markHumanIntervention(flow.getId()) == 0) {
                continue;
            }
            log.error("【STOCK_FLOW_ALERT】库存扣减流水卡在「补偿中」超过 {} 分钟，已置人工介入：" +
                            "flowId={}, orderSn={}, skuId={}, changeNum={}；" +
                            "请先核对 ware_sku.locked_stock 与 ware_order_task.status 判断扣减是否已发生，" +
                            "再用 POST /internal/ware/flow/manual?id={}&action=reset（重新补偿）或 action=done（确认已修复）处置",
                    STUCK_COMPENSATING_MINUTES, flow.getId(), flow.getOrderSn(), flow.getSkuId(),
                    flow.getChangeNum(), flow.getId());
        }
    }

    /** 对单条流水补偿：抢占（0→2）成功才处理；失败置 3 并告警。 */
    private void compensateOne(StockFlowLog flow) {
        // 多实例下以此条件更新抢占：影响 0 行 = 已被其他实例/任务处理，跳过
        int claimed = stockFlowLogMapper.claimCompensating(flow.getId());
        if (claimed == 0) {
            return;
        }
        try {
            doCompensate(flow);
        } catch (Exception e) {
            humanFail(flow, "补偿过程异常：" + e.getMessage());
        }
    }

    private void doCompensate(StockFlowLog flow) {
        WareSku sku = wareSkuMapper.selectOne(new LambdaQueryWrapper<WareSku>()
                .eq(WareSku::getSkuId, flow.getSkuId()).last("LIMIT 1"));
        if (sku == null) {
            humanFail(flow, "SKU 不存在：skuId=" + flow.getSkuId());
            return;
        }
        Integer raw = flow.getChangeNum();
        int num = raw == null ? 0 : -raw;
        if (num <= 0) {
            humanFail(flow, "流水数量非法（changeNum=" + raw + "，仅允许负数扣减）");
            return;
        }
        // 不限量 SKU：无真实库存可扣，直接视补偿成功（与正常扣减一致，不落数值变化）
        if (Integer.valueOf(1).equals(sku.getUnlimited())) {
            completeCompensation(flow, sku.getShopId());
            return;
        }
        // 重放扣减 = 把该单的“锁定占用”转“真实售出”；锁定已不存在（原扣减已发生）时影响 0 行 → 人工介入
        int rows = wareSkuMapper.deductStock(sku.getSkuId(), sku.getShopId(), num);
        if (rows == 0) {
            humanFail(flow, "重放扣减失败：库存不足或该单锁定已被扣减（避免重复扣，请人工核对订单实际扣减情况）");
            return;
        }
        completeCompensation(flow, sku.getShopId());
    }

    /** 补偿成功：流水 2→1，并把库存任务置为“已扣减”，保证后续退款/回补状态机一致。 */
    private void completeCompensation(StockFlowLog flow, Long shopId) {
        // 同步库存任务状态机（同正常支付扣减语义），避免退款回补时因任务仍停留在“已锁定”而漏回补
        WareOrderTask taskUpdate = new WareOrderTask();
        taskUpdate.setStatus(TASK_DEDUCTED);
        wareOrderTaskMapper.update(taskUpdate, new LambdaQueryWrapper<WareOrderTask>()
                .eq(WareOrderTask::getOrderSn, flow.getOrderSn())
                .eq(WareOrderTask::getSkuId, flow.getSkuId())
                .eq(WareOrderTask::getShopId, shopId));
        stockFlowLogMapper.complete(flow.getId());
        log.info("库存流水补偿完成：flowId={}, orderSn={}, skuId={}", flow.getId(), flow.getOrderSn(), flow.getSkuId());
    }

    /** 补偿失败：流水 2→3（人工介入），并触发告警（含 orderSn/skuId/原因）。 */
    private void humanFail(StockFlowLog flow, String reason) {
        stockFlowLogMapper.markHumanIntervention(flow.getId());
        log.error("【STOCK_FLOW_ALERT】库存扣减流水补偿失败，需人工介入：" +
                        "flowId={}, orderSn={}, skuId={}, reason={}",
                flow.getId(), flow.getOrderSn(), flow.getSkuId(), reason);
    }
}
