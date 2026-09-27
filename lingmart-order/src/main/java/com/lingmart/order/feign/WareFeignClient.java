package com.lingmart.order.feign;

import com.lingmart.common.dto.StockLockRequest;
import com.lingmart.common.result.R;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * 仓储服务 Feign 客户端：锁库存 / 解锁 / 回补。
 *
 * <p>锁库存在下单的 Seata 全局事务内调用，XID 由 seata 自动传播。
 * 「支付扣减」已改为 Outbox + MQ 事件异步消费，order 不再同步调用 deduct（见 OrderPaidOutboxRelay）。</p>
 */
@FeignClient(name = "lingmart-ware")
public interface WareFeignClient {

    @PostMapping("/internal/ware/lock")
    R<Void> lockStock(@RequestBody StockLockRequest request);

    @PostMapping("/internal/ware/unlock")
    R<Void> unlockStock(@RequestBody StockLockRequest request);

    @PostMapping("/internal/ware/restock")
    R<Void> restockStock(@RequestBody StockLockRequest request);
}
