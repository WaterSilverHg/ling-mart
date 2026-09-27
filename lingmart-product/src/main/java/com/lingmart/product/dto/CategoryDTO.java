package com.lingmart.product.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 新增分类请求参数。
 */
@Data
public class CategoryDTO {

    /** 分类名称 */
    @NotBlank(message = "分类名称不能为空")
    @Size(max = 30, message = "分类名称最长 30 个字符")
    private String name;

    /** 父分类 ID（0 为顶级） */
    private Long parentId;

    /** 排序 */
    private Integer sort;
}
