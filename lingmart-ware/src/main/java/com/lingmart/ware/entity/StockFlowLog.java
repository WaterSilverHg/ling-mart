package com.lingmart.ware.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 库存流水日志实体（stock_flow_log）。
 *
 * <p>ware 消费「订单支付成功」MQ 事件幂等扣减后，在**同一事务**写入一条 {@code change_num<0} 且
 * {@code status=1}（已完成）的流水（正常路径一次成型）；仅异常/历史遗留的 {@code status=0} 流水
 * 由定时任务重放扣减并推进状态机：0待处理 → 2补偿中 → 1已完成 / 3人工介入。</p>
 *
 * <p>id 保留数据库自增：本表为内部对账表、id 不对外下发，无雪花/枚举需求，按项目早期 DDL 约定自增。</p>
 */
@Data
@TableName("stock_flow_log")
public class StockFlowLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 商品 SKU ID（= ware_sku.sku_id） */
    private Long skuId;

    /** 关联订单号 */
    private String orderSn;

    /** 变化量：负数为扣减（正数场景不走本表，见取消/退款独立链路） */
    private Integer changeNum;

    /** 状态：0=待处理 1=已完成 2=补偿中 3=人工介入 */
    private Integer status;

    /** 乐观锁版本号（预留；当前以 status CAS 抢占，见 StockFlowLogMapper） */
    private Integer version;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
