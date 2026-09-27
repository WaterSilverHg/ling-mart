package com.lingmart.product.es;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.lingmart.product.entity.EsWriteRetry;
import com.lingmart.product.mapper.EsWriteRetryMapper;
import com.lingmart.product.service.ProductService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * ES 写失败补偿定时任务。
 *
 * <p>扫描 {@code es_write_retry} 表中到期的待重试记录，按原动作重放：
 * <ul>
 *   <li>INDEX：<b>按 spuId 重查 DB 重新组装文档</b>后写入 ES（不再重放失败当时的旧快照 ——
 *       否则销量回退、店铺改名后，早先落下的重试会把过期数据写回 ES，覆盖刚修正的结果）；
 *       若 SPU 已删除或已下架，则改为删除 ES 文档；</li>
 *   <li>DELETE：按 spuId 从 ES 删除。</li>
 * </ul>
 * 注：{@code docJson} 列保留仅作诊断，不再用于重放。
 * 成功置状态 1；失败次数 +1 并按指数退避延后下次重试（2^n 分钟、上限 2 小时）；
 * 重试次数超限置状态 2（放弃），留待人工介入。依赖 {@code @EnableScheduling}。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EsWriteRetryScheduler {

    /** 最大重试次数（超过后放弃，避免无限循环消耗） */
    private static final int MAX_ATTEMPTS = 20;

    /** 单批处理上限（防一次扫描拖垮 ES） */
    private static final int BATCH_LIMIT = 100;

    /** 退避时长上限：2 小时 */
    private static final long MAX_BACKOFF_MINUTES = 120;

    private final EsWriteRetryMapper retryMapper;
    private final ElasticsearchOperations es;
    private final ProductService productService;

    /**
     * 周期扫描到期补偿记录并重放（扫描间隔可经 {@code lingmart.es.retry-scan-ms} 配置）。
     */
    @Scheduled(fixedDelayString = "${lingmart.es.retry-scan-ms:60000}")
    public void retryPending() {
        List<EsWriteRetry> pending = retryMapper.selectList(Wrappers.<EsWriteRetry>lambdaQuery()
                .eq(EsWriteRetry::getStatus, 0)
                .le(EsWriteRetry::getNextRetryTime, LocalDateTime.now())
                .orderByAsc(EsWriteRetry::getNextRetryTime)
                .last("LIMIT " + BATCH_LIMIT));
        if (pending.isEmpty()) {
            return;
        }
        int success = 0;
        for (EsWriteRetry retry : pending) {
            if (doRetry(retry)) {
                success++;
            }
        }
        log.info("ES 补偿扫描：扫描 {} 条，成功 {} 条", pending.size(), success);
    }

    /** 执行单条重放；返回是否成功 */
    private boolean doRetry(EsWriteRetry retry) {
        try {
            if (EsProductService.ACTION_INDEX.equals(retry.getAction())) {
                // 以 DB 为准重建文档（非重放旧快照）：避免把过期销量/店名写回 ES
                EsProduct doc = productService.loadEsDoc(retry.getSpuId());
                if (doc == null) {
                    // 商品已删除或已下架：重放目标不存在，改为删除 ES 文档（幂等）
                    es.delete(String.valueOf(retry.getSpuId()), EsProduct.class);
                } else {
                    es.save(doc);
                }
            } else if (EsProductService.ACTION_DELETE.equals(retry.getAction())) {
                es.delete(String.valueOf(retry.getSpuId()), EsProduct.class);
            } else {
                // 未知动作：直接放弃，避免死循环
                markAbandoned(retry, "未知动作：" + retry.getAction());
                return false;
            }
            markSuccess(retry);
            return true;
        } catch (Exception e) {
            markFailure(retry, e.getMessage());
            return false;
        }
    }

    private void markSuccess(EsWriteRetry retry) {
        EsWriteRetry update = new EsWriteRetry();
        update.setId(retry.getId());
        update.setStatus(1);
        retryMapper.updateById(update);
        log.info("ES 补偿重放成功：id={}, spuId={}, action={}", retry.getId(), retry.getSpuId(), retry.getAction());
    }

    private void markFailure(EsWriteRetry retry, String error) {
        int retried = (retry.getRetryCount() == null ? 0 : retry.getRetryCount()) + 1;
        if (retried >= MAX_ATTEMPTS) {
            markAbandoned(retry, error);
            return;
        }
        EsWriteRetry update = new EsWriteRetry();
        update.setId(retry.getId());
        update.setRetryCount(retried);
        update.setLastError(truncate(error, 500));
        update.setNextRetryTime(LocalDateTime.now().plus(backoff(retried)));
        retryMapper.updateById(update);
        log.warn("ES 补偿重放失败，下次退避重试：id={}, spuId={}, retryCount={}, err={}",
                retry.getId(), retry.getSpuId(), retried, error);
    }

    private void markAbandoned(EsWriteRetry retry, String error) {
        EsWriteRetry update = new EsWriteRetry();
        update.setId(retry.getId());
        update.setStatus(2);
        update.setLastError(truncate(error, 500));
        retryMapper.updateById(update);
        log.error("ES 补偿重试超限，已放弃待人工介入：id={}, spuId={}, action={}, err={}",
                retry.getId(), retry.getSpuId(), retry.getAction(), error);
    }

    /** 指数退避：2^n 分钟，上限 MAX_BACKOFF_MINUTES */
    private Duration backoff(int attempt) {
        long minutes = Math.min(1L << Math.min(attempt, 20), MAX_BACKOFF_MINUTES);
        return Duration.ofMinutes(Math.max(minutes, 1));
    }

    private String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }
}
