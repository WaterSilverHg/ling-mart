package com.lingmart.ware.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lingmart.ware.entity.StockFlowLog;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 库存流水 Mapper：状态机全部走「条件更新（CAS）」，杜绝重复消费/重复补偿。
 */
public interface StockFlowLogMapper extends BaseMapper<StockFlowLog> {

    /**
     * 抢占补偿：0待处理 → 2补偿中（条件更新，多实例/多任务下只有一个生效）。
     *
     * @param id 流水主键
     * @return 影响行数（0=已被其他实例抢占/状态已变，跳过）
     */
    @Update("UPDATE stock_flow_log SET status = 2, update_time = NOW() WHERE id = #{id} AND status = 0")
    int claimCompensating(@Param("id") Long id);

    /**
     * 补偿成功：2补偿中 → 1已完成。
     *
     * @param id 流水主键
     * @return 影响行数
     */
    @Update("UPDATE stock_flow_log SET status = 1, update_time = NOW() WHERE id = #{id} AND status = 2")
    int complete(@Param("id") Long id);

    /**
     * 补偿失败（库存不足/SKU 不存在/异常）：2补偿中 → 3人工介入，不再自动重试。
     *
     * @param id 流水主键
     * @return 影响行数
     */
    @Update("UPDATE stock_flow_log SET status = 3, update_time = NOW() WHERE id = #{id} AND status = 2")
    int markHumanIntervention(@Param("id") Long id);

    /**
     * 人工处置：3人工介入 → 0待处理（让定时任务重新尝试补偿）。
     *
     * @param id 流水主键
     * @return 影响行数（0=状态已变化，如已被处置）
     */
    @Update("UPDATE stock_flow_log SET status = 0, version = version + 1, update_time = NOW() " +
            "WHERE id = #{id} AND status = 3")
    int resetToPending(@Param("id") Long id);

    /**
     * 人工处置：3人工介入 → 1已完成（运维确认数据已人工修复）。
     *
     * @param id 流水主键
     * @return 影响行数（0=状态已变化）
     */
    @Update("UPDATE stock_flow_log SET status = 1, version = version + 1, update_time = NOW() " +
            "WHERE id = #{id} AND status = 3")
    int markDone(@Param("id") Long id);
}
