package com.lingmart.product.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 审核审计日志（谁在何时审了谁、结果与原因）。
 */
@Data
@TableName("audit_log")
public class AuditLog {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 操作人用户ID（管理员） */
    private Long operatorId;

    /** 动作：PRODUCT_REVIEW 商品审核 */
    private String action;

    /** 被审对象ID（商品SPU ID） */
    private Long targetId;

    /** 结果：1通过 0拒绝 */
    private Integer result;

    /** 拒绝原因（通过可为空） */
    private String reason;

    private LocalDateTime createTime;
}
