package com.lingmart.order.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 卖家经营数据统计视图。
 *
 * <p>聚合当前卖家店铺的订单数据，供卖家中心「数据统计」看板展示。
 * 统计口径：{@code order_status IN (1,2,3)}（已支付/已发货/已完成）视为成交，
 * 已取消与待支付不计入销售额。统计范围由 range 决定（7d / 30d / all）。</p>
 */
@Data
public class SellerStatsVO {

    /** 统计范围回显：7d / 30d / all */
    private String range;

    /** 概览（期内容额口径） */
    private Overview overview;

    /** 订单状态分布（全部订单，含待支付/已取消） */
    private List<StatusCount> statusDistribution;

    /** 销售趋势（期内按支付日期逐日） */
    private List<TrendPoint> salesTrend;

    /** 热销商品 TOP5（按成交件数） */
    private List<TopProduct> topProducts;

    /** 概览聚合 */
    @Data
    public static class Overview {
        /** 成交订单数 */
        private Long orderCount;
        /** 成交销售额 */
        private BigDecimal salesAmount;
        /** 成交件数 */
        private Long itemCount;
        /** 客单价（销售额 / 订单数，无订单为 0） */
        private BigDecimal avgAmount;
    }

    /** 状态分布一项 */
    @Data
    public static class StatusCount {
        /** 订单状态：0待支付 1已支付 2已发货 3已完成 4已取消 */
        private Integer status;
        /** 该状态订单数 */
        private Long count;
    }

    /** 销售趋势一天 */
    @Data
    public static class TrendPoint {
        /** 支付日期（yyyy-MM-dd） */
        private String date;
        /** 当日订单数 */
        private Long orderCount;
        /** 当日销售额 */
        private BigDecimal amount;
    }

    /** 热销商品一项 */
    @Data
    public static class TopProduct {
        private Long spuId;
        private String spuTitle;
        private Long skuId;
        private String skuTitle;
        /** 成交件数 */
        private Long num;
        /** 成交金额 */
        private BigDecimal amount;
    }
}
