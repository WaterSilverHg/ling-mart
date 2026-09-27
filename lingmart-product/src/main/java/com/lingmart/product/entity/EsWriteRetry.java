package com.lingmart.product.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * ES 写失败补偿记录。
 *
 * <p>商品写入/删除 Elasticsearch 失败时落表，由 {@code EsWriteRetryScheduler} 定时按指数退避
 * 重放，保证 ES 与 MySQL 最终一致。状态：0 待重试 / 1 成功 / 2 放弃（重试超限，需人工介入）。</p>
 */
@Data
@TableName("es_write_retry")
public class EsWriteRetry {

    /** 主键（雪花，应用侧分配） */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 商品 SPU ID */
    private Long spuId;

    /** 动作：INDEX 写入 / DELETE 删除 */
    private String action;

    /** INDEX 动作的完整检索文档 JSON（DELETE 动作可空） */
    private String docJson;

    /** 状态：0 待重试 / 1 成功 / 2 放弃 */
    private Integer status;

    /** 已重试次数 */
    private Integer retryCount;

    /** 最近一次失败原因 */
    private String lastError;

    /** 下次重试时间（指数退避） */
    private LocalDateTime nextRetryTime;

    /** 创建时间 */
    private LocalDateTime createTime;

    /** 更新时间 */
    private LocalDateTime updateTime;
}
