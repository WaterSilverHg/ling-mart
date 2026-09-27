package com.lingmart.ware.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 库存锁定任务实体（ware_order_task）。
 *
 * <p>记录每个订单对每个 SKU 的锁定状态机：0已锁定 → 1已扣减（支付）/ 2已解锁（取消）/ 3已退款回补。</p>
 */
@Data
@TableName("ware_order_task")
public class WareOrderTask {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 订单号 */
    private String orderSn;

    /** 商品SKU ID */
    private Long skuId;

    /** 所属店铺ID */
    private Long shopId;

    /** 数量 */
    private Integer num;

    /** 状态：0已锁定 1已扣减 2已解锁 */
    private Integer status;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
