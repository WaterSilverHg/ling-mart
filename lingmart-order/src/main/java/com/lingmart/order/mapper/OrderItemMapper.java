package com.lingmart.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lingmart.order.entity.OrderItem;
import com.lingmart.order.vo.SellerStatsVO;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 订单明细 Mapper。
 *
 * <p>常规 CRUD 由 MyBatis-Plus 提供；热销商品/成交件数统计用 {@code @Select} 联表查询。</p>
 */
public interface OrderItemMapper extends BaseMapper<OrderItem> {

    /**
     * 卖家热销商品 TOP5（期内已支付订单，按成交件数倒序）。
     *
     * @param shopId 店铺ID
     * @param since  起始时间（null 表示不限）
     * @return 热销商品列表
     */
    @Select("<script>" +
            "SELECT oi.spu_id, oi.spu_title, oi.sku_id, oi.sku_title, " +
            "SUM(oi.num) AS num, SUM(oi.total_price) AS amount " +
            "FROM order_item oi JOIN `order` o ON o.id = oi.order_id " +
            "WHERE o.shop_id = #{shopId} AND o.order_status IN (1,2,3) " +
            "<if test='since != null'>AND o.pay_time &gt;= #{since}</if> " +
            "GROUP BY oi.spu_id, oi.spu_title, oi.sku_id, oi.sku_title " +
            "ORDER BY num DESC LIMIT 5</script>")
    List<SellerStatsVO.TopProduct> selectTopProducts(@Param("shopId") Long shopId, @Param("since") LocalDateTime since);

    /**
     * 卖家成交件数（期内已支付订单的商品件数合计）。
     *
     * @param shopId 店铺ID
     * @param since  起始时间（null 表示不限）
     * @return 成交件数
     */
    @Select("<script>" +
            "SELECT COALESCE(SUM(oi.num),0) " +
            "FROM order_item oi JOIN `order` o ON o.id = oi.order_id " +
            "WHERE o.shop_id = #{shopId} AND o.order_status IN (1,2,3) " +
            "<if test='since != null'>AND o.pay_time &gt;= #{since}</if></script>")
    Long selectSellerItemCount(@Param("shopId") Long shopId, @Param("since") LocalDateTime since);
}
