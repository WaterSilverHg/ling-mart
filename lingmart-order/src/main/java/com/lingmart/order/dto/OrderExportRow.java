package com.lingmart.order.dto;

import com.alibaba.excel.annotation.ExcelProperty;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 订单导出行（Excel）。
 *
 * <p>一行对应一个订单明细（同订单多商品则多行）。用于卖家/买家订单导出，
 * {@code @ExcelProperty} 定义中文表头，列顺序与字段声明一致。</p>
 */
@Data
public class OrderExportRow {

    /** 订单号（mapper 映射，导出列不展示） */
    private String orderSn;

    /** 下单时间 */
    @ExcelProperty("下单时间")
    private LocalDateTime createTime;

    /** 商品名称（SPU 标题） */
    @ExcelProperty("商品")
    private String spuTitle;

    /** 规格（SKU 标题） */
    @ExcelProperty("规格")
    private String skuTitle;

    /** 成交单价 */
    @ExcelProperty("单价")
    private BigDecimal price;

    /** 数量 */
    @ExcelProperty("数量")
    private Integer num;

    /** 小计金额 */
    @ExcelProperty("小计")
    private BigDecimal totalPrice;

    /** 订单状态码（mapper 映射，导出时由 service 转为中文文本写入 statusText） */
    private Integer orderStatus;

    /** 订单状态（中文文本） */
    @ExcelProperty("订单状态")
    private String statusText;

    /** 支付时间 */
    @ExcelProperty("支付时间")
    private LocalDateTime payTime;

    /** 买家备注 */
    @ExcelProperty("买家备注")
    private String buyerRemark;
}
