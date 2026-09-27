package com.lingmart.product.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 新增品牌请求参数。
 */
@Data
public class BrandDTO {

    /** 品牌名称 */
    @NotBlank(message = "品牌名称不能为空")
    @Size(max = 30, message = "品牌名称最长 30 个字符")
    private String name;

    /** 品牌 logo 地址 */
    private String logo;
}
