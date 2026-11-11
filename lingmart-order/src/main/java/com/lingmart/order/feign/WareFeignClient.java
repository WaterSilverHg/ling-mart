package com.lingmart.order.feign;

import com.lingmart.common.dto.StockLockRequest;
import com.lingmart.common.result.R;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * 仓储服务 Feign 客户端：锁库存 / 解锁 / 回补。
 *
 * <p>锁库存在下单的 Seata 全局事务内调用。⚠️ <b>XID 不是 Seata 自动传播的</b>——Seata 2.5 只提供
 * 服务端「读 {@code TX_XID} 头并绑定 RootContext」的拦截器，不提供 Feign 客户端集成；
 * 客户端写头这一环由 lingmart-common 的 {@code SeataXidFeignInterceptor} 完成
 * （自动配置，具备 Feign + Seata 的服务生效）。此处曾有注释写「XID 由 seata 自动传播」，
 * 正是这句未经验证的假设让跨服务传播长期缺失、ware 实际从未注册成分支。</p>
 *
 * <p>「支付扣减」已改为 Outbox + MQ 事件异步消费，order 不再同步调用 deduct（见 OrderPaidOutboxRelay）；
 * 那条链路走 MQ 消费者、无 XID，ware 侧靠幂等与流水补偿保证正确。</p>
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
