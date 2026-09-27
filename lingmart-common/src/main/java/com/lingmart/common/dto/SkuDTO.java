package com.lingmart.common.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * SKU 请求参数（随 SPU 一并提交）。
 *
 * <p>跨服务共享：lingmart-product 发布/编辑商品、lingmart-shop 开店申请携带初始商品清单均使用。</p>
 */
@Data
public class SkuDTO {

    /** SKU ID（仅库存更新时使用；发布/草稿场景忽略） */
    private Long id;

    /** SKU 名称（如：黑色/128G） */
    @NotBlank(message = "SKU 名称不能为空")
    @Size(max = 100, message = "SKU 名称最长 100 个字符")
    private String title;

    /** 价格（元） */
    @NotNull(message = "价格不能为空")
    @DecimalMin(value = "0.01", message = "价格需大于 0")
    private java.math.BigDecimal price;

    /**
     * 数量：NULL 表示不限量；0..N 为限量可售（0 = 售罄）。
     *
     * <p>发布/申请/内容重审/改库存共用。改动跨模块，请勿把「NULL」再当作「保持原值」；
     * 改库存接口（PUT /api/product/{id}/stock）中 NULL 即表示把该 SKU 置为不限量。</p>
     */
    @Min(value = 0, message = "数量不能为负数")
    private Integer stock;

    /** 图片地址列表 */
    private List<String> images;
}
