package com.lingmart.product.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 销量回退幂等记录（一个退款订单一条）。
 *
 * <p>退款审核通过时需回退销量，而「product 已回退但响应超时 → 订单 Seata 全局事务回滚 →
 * 卖家重新审核通过」会造成二次扣减：回退前先插入本表（{@code order_sn} 唯一键），
 * 重复键即视为该订单已回退过并直接跳过。</p>
 *
 * <p>本记录与销量扣减处于同一本地事务：扣减失败会一并回滚，因此仍可安全重试。</p>
 */
@Data
@TableName("sale_rollback_log")
public class SaleRollbackLog {

    /** 主键（雪花，应用侧分配） */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 订单号（唯一：同一订单只允许回退一次） */
    private String orderSn;

    /** 创建时间 */
    private LocalDateTime createTime;
}
