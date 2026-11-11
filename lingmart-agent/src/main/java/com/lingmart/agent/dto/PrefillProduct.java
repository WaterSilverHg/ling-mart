package com.lingmart.agent.dto;

import lombok.Data;

import java.util.List;

/**
 * 预填的商品条目（对应前端 {@code ProductEditor} 的 model 结构）。
 *
 * <p>与后端 {@code SpuDTO} 的对应关系：{@code title/subtitle/description} 同名；
 * 本类**刻意不含图片字段**（{@code mainImage} / {@code images}）——图片必须是已上传的 URL，
 * 模型生成不了，留空由用户自己传。</p>
 */
@Data
public class PrefillProduct {

    /** 商品标题（必填，≤100 字符） */
    private String title;

    /** 商品副标题（可选，≤255 字符） */
    private String subtitle;

    /** 商品详情（可选，≤2000 字符） */
    private String description;

    /**
     * 规格列表（至少 1 条）。
     *
     * <p>若文档里只有一个价格，模型应生成**一条**规格（如规格名「默认」）。</p>
     */
    private List<PrefillSku> skus;
}
