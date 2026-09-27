package com.lingmart.product.es;

import com.lingmart.product.event.SaleUpdateEvent;
import com.lingmart.product.service.ProductService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 销量更新事件监听：事务提交后异步刷新 ES 销量。
 *
 * <p>在本地事务提交后（AFTER_COMMIT）触发并用独立线程执行，DB 侧 sale_count 已落库，
 * 故读到的销量是最终值；即使 ES/店名 Feign 有网络 IO 也不阻塞支付全局事务与 SPU 行锁。
 * 线程池见 {@code config/AsyncConfig}（有界队列 + CallerRunsPolicy，避免无界堆积）。
 * 内部 refreshEsSalesIndex 已 fail-soft（EsProductService 失败落 es_write_retry 补偿表）。</p>
 *
 * <p>{@code fallbackExecution = true}：发布方没有事务时也执行刷新。当前两个发布点（支付累加、
 * 退款回退）都在事务内，所以行为不变；但缺省值 false 会让「将来在事务外发事件」变成静默丢刷新，
 * 属埋雷，这里显式打开。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SalesEsRefreshListener {

    private final ProductService productService;

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onSaleUpdated(SaleUpdateEvent event) {
        if (event == null || event.items() == null || event.items().isEmpty()) {
            return;
        }
        productService.refreshEsSalesIndex(event.items());
    }
}
