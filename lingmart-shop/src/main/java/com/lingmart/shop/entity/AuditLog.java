package com.lingmart.shop.entity;

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

    /** 动作：APPLY_REVIEW 入驻审核 / SHOP_REVIEW 店铺信息审核 */
    private String action;

    /** 被审对象ID（申请ID / 店铺ID） */
    private Long targetId;

    /** 结果：1通过 0拒绝 */
    private Integer result;

    /** 拒绝原因（通过可为空） */
    private String reason;

    private LocalDateTime createTime;
}
