package com.lingmart.agent.dto;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 预填的商品规格（对应前端 {@code ProductEditor} 里的一行规格）。
 *
 * <p>字段语义与后端 {@code SkuDTO} 一致：{@code price} 必须 ≥ 0.01；
 * {@code qty} 为 null 表示**不限量**（后端映射为 {@code stock = null}）。</p>
 */
@Data
public class PrefillSku {

    /** 规格名（必填，≤100 字符；文档里没有规格区分时填「默认」） */
    private String title;

    /** 价格（必填，≥ 0.01） */
    private BigDecimal price;

    /** 数量：null = 不限量；0..N = 限量可售（0 表示售罄） */
    private Integer qty;
}
