package com.lingmart.product.es;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lingmart.product.entity.EsWriteRetry;
import com.lingmart.product.mapper.EsWriteRetryMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 商品检索文档写入服务。
 *
 * <p>商品上架时写入 ES、下架时删除。写入失败不中断业务（ES 暂不可用时商品仍可正常上下架），
 * 同时落 {@code es_write_retry} 补偿表，由 {@link EsWriteRetryScheduler} 定时重放，
 * 保证 ES 与 MySQL 最终一致。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EsProductService {

    /** 动作：写入索引 */
    public static final String ACTION_INDEX = "INDEX";
    /** 动作：删除索引 */
    public static final String ACTION_DELETE = "DELETE";

    private final ElasticsearchOperations es;
    private final EsWriteRetryMapper esWriteRetryMapper;
    private final ObjectMapper objectMapper;

    /**
     * 写入（覆盖）商品检索文档。
     *
     * @param doc 商品检索文档
     */
    public void index(EsProduct doc) {
        try {
            es.save(doc);
            log.info("商品已写入 ES：spuId={}", doc.getSpuId());
        } catch (Exception e) {
            log.error("商品写入 ES 失败，已记录补偿：spuId={}, err={}", doc.getSpuId(), e.getMessage());
            recordRetry(doc.getSpuId(), ACTION_INDEX, toJson(doc), e.getMessage());
        }
    }

    /**
     * 批量写入（覆盖）商品检索文档。
     *
     * <p>用 {@code save(Iterable)}（bulk API）一次写入多个文档，减少网络往返；bulk 整体失败时
     * 降级为逐条 {@link #index}，保证单个文档失败不拖累其余（与单条写入同等的容错语义）。</p>
     *
     * @param docs 商品检索文档列表
     */
    public void indexAll(List<EsProduct> docs) {
        if (docs == null || docs.isEmpty()) {
            return;
        }
        try {
            // 类型见证 <EsProduct> 强制选中 save(Iterable<T>) 的 bulk 重载，
            // 避免与 save(T)（会把 List 误当单个实体）的重载歧义
            es.<EsProduct>save(docs);
            log.info("商品批量写入 ES：count={}", docs.size());
        } catch (Exception e) {
            log.error("商品批量写入 ES 失败，降级逐条写入：count={}, err={}", docs.size(), e.getMessage());
            docs.forEach(this::index);
        }
    }

    /**
     * 删除商品检索文档（下架时调用）。
     *
     * @param spuId 商品 ID
     */
    public void delete(String spuId) {
        try {
            es.delete(spuId, EsProduct.class);
            log.info("商品已从 ES 删除：spuId={}", spuId);
        } catch (Exception e) {
            log.error("商品从 ES 删除失败，已记录补偿：spuId={}, err={}", spuId, e.getMessage());
            recordRetry(spuId, ACTION_DELETE, null, e.getMessage());
        }
    }

    /**
     * 检查某商品文档是否存在于 ES（对账任务用）。
     *
     * @param spuId 商品 ID（字符串）
     * @return true 存在 / false 缺失（需补索引）；检查失败返回 null（对账跳过该条，避免 ES 故障时误刷重试）
     */
    public Boolean exists(String spuId) {
        try {
            return es.exists(spuId, EsProduct.class);
        } catch (Exception e) {
            log.warn("检查 ES 文档存在性失败（对账跳过该条）：spuId={}, err={}", spuId, e.getMessage());
            return null;
        }
    }

    /**
     * 记录 ES 写失败补偿（best-effort：补偿表自身写失败不影响主流程）。
     *
     * @param spuId   商品 ID（字符串）
     * @param action  动作（INDEX / DELETE）
     * @param docJson 检索文档 JSON（DELETE 可为 null）
     * @param error   失败原因
     */
    private void recordRetry(String spuId, String action, String docJson, String error) {
        try {
            EsWriteRetry retry = new EsWriteRetry();
            retry.setSpuId(Long.valueOf(spuId));
            retry.setAction(action);
            retry.setDocJson(docJson);
            retry.setStatus(0); // 待重试
            retry.setRetryCount(0);
            retry.setLastError(truncate(error, 500));
            retry.setNextRetryTime(LocalDateTime.now().plusMinutes(1)); // 首次 1 分钟后重试
            esWriteRetryMapper.insert(retry);
            log.info("已写入 ES 补偿记录：spuId={}, action={}", spuId, action);
        } catch (Exception e) {
            // 补偿表不可用（库异常）时仅告警，仍不阻断主流程
            log.error("记录 ES 补偿失败（不影响业务）：spuId={}, action={}, err={}", spuId, action, e.getMessage());
        }
    }

    /** 序列化检索文档（失败返回 null，不影响补偿流程） */
    private String toJson(EsProduct doc) {
        try {
            return objectMapper.writeValueAsString(doc);
        } catch (JsonProcessingException e) {
            log.warn("序列化 ES 文档失败：spuId={}, err={}", doc.getSpuId(), e.getMessage());
            return null;
        }
    }

    private String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }
}
