package com.lingmart.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lingmart.order.dto.OrderExportRow;
import com.lingmart.order.entity.Order;
import com.lingmart.order.vo.SellerStatsVO;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.cursor.Cursor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 订单 Mapper。
 *
 * <p>常规 CRUD 由 MyBatis-Plus 提供；统计与导出聚合查询使用 {@code @Select} 注解
 * （动态时间范围用 &lt;script&gt; 内 &lt;if&gt; 拼接）。</p>
 */
public interface OrderMapper extends BaseMapper<Order> {

    /**
     * 卖家成交概览（期内已支付订单：订单数 + 销售额）。
     *
     * @param shopId 店铺ID
     * @param since  起始时间（null 表示不限）
     * @return 概览聚合
     */
    @Select("<script>" +
            "SELECT COUNT(*) AS orderCount, COALESCE(SUM(total_amount),0) AS salesAmount " +
            "FROM `order` " +
            "WHERE shop_id = #{shopId} AND order_status IN (1,2,3) " +
            "<if test='since != null'>AND pay_time &gt;= #{since}</if>" +
            "</script>")
    SellerStatsVO.Overview selectSellerOverview(@Param("shopId") Long shopId, @Param("since") LocalDateTime since);

    /**
     * 卖家订单状态分布（全部订单，按状态分组计数）。
     *
     * @param shopId 店铺ID
     * @param since  起始时间（null 表示不限）
     * @return 状态分布列表
     */
    @Select("<script>" +
            "SELECT order_status AS status, COUNT(*) AS count FROM `order` " +
            "WHERE shop_id = #{shopId} " +
            "<if test='since != null'>AND create_time &gt;= #{since}</if> " +
            "GROUP BY order_status</script>")
    List<SellerStatsVO.StatusCount> selectStatusDistribution(@Param("shopId") Long shopId, @Param("since") LocalDateTime since);

    /**
     * 卖家销售趋势（期内已支付订单，按支付日期逐日聚合）。
     *
     * @param shopId 店铺ID
     * @param since  起始时间（null 表示不限）
     * @return 逐日趋势列表
     */
    @Select("<script>" +
            "SELECT DATE_FORMAT(pay_time, '%Y-%m-%d') AS `date`, COUNT(*) AS orderCount, " +
            "COALESCE(SUM(total_amount),0) AS amount " +
            "FROM `order` " +
            "WHERE shop_id = #{shopId} AND order_status IN (1,2,3) " +
            "<if test='since != null'>AND pay_time &gt;= #{since}</if> " +
            "GROUP BY `date` ORDER BY `date`</script>")
    List<SellerStatsVO.TrendPoint> selectSalesTrend(@Param("shopId") Long shopId, @Param("since") LocalDateTime since);

    /**
     * 卖家订单导出数据（一行一个订单明细，游标流式读取）。
     *
     * <p>返回 {@link Cursor} 供服务层逐行消费（配合 {@code @Transactional(readOnly=true)}
     * 保持 SqlSession 存活，大订单量不整体加载进内存）。</p>
     *
     * @param shopId 店铺ID
     * @param since  起始时间（null 表示不限）
     * @return 导出行游标
     */
    @Select("<script>" +
            "SELECT o.order_sn, o.create_time, oi.spu_title, oi.sku_title, oi.price, oi.num, oi.total_price, " +
            "o.order_status, o.pay_time, o.buyer_remark " +
            "FROM `order` o JOIN order_item oi ON oi.order_id = o.id " +
            "WHERE o.shop_id = #{shopId} " +
            "<if test='since != null'>AND o.create_time &gt;= #{since}</if> " +
            "ORDER BY o.create_time DESC</script>")
    Cursor<OrderExportRow> selectSellerOrdersForExport(@Param("shopId") Long shopId, @Param("since") LocalDateTime since);

    /**
     * 买家订单导出数据（一行一个订单明细，游标流式读取）。
     *
     * @param buyerId 买家用户ID
     * @param since   起始时间（null 表示不限）
     * @return 导出行游标
     */
    @Select("<script>" +
            "SELECT o.order_sn, o.create_time, oi.spu_title, oi.sku_title, oi.price, oi.num, oi.total_price, " +
            "o.order_status, o.pay_time, o.buyer_remark " +
            "FROM `order` o JOIN order_item oi ON oi.order_id = o.id " +
            "WHERE o.buyer_id = #{buyerId} " +
            "<if test='since != null'>AND o.create_time &gt;= #{since}</if> " +
            "ORDER BY o.create_time DESC</script>")
    Cursor<OrderExportRow> selectBuyerOrdersForExport(@Param("buyerId") Long buyerId, @Param("since") LocalDateTime since);
}
