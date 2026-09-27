package com.lingmart.common.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 商品 SPU 请求参数（发布/编辑商品，含 SKU 列表）。
 *
 * <p>跨服务共享：lingmart-product 发布/编辑商品、lingmart-shop 开店申请携带初始商品清单均使用。</p>
 */
@Data
public class SpuDTO {

    /** 商品标题 */
    @NotBlank(message = "商品标题不能为空")
    @Size(max = 100, message = "商品标题最长 100 个字符")
    private String title;

    /** 副标题/卖点 */
    @Size(max = 255, message = "副标题最长 255 个字符")
    private String subtitle;

    /** 分类 ID */
    private Long categoryId;

    /** 品牌 ID */
    private Long brandId;

    /** 主图地址 */
    @Size(max = 255, message = "主图地址过长")
    private String mainImage;

    /** 商品详情 */
    private String description;

    /** SKU 列表（至少一个） */
    @NotEmpty(message = "至少需要一个 SKU")
    @Valid
    private List<SkuDTO> skus;
}
