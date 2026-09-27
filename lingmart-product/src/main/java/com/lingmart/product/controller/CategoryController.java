package com.lingmart.product.controller;

import com.lingmart.common.result.R;
import com.lingmart.product.entity.Category;
import com.lingmart.product.service.CatalogService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 商品分类接口（公开浏览）。
 *
 * <p>分类列表已登录用户可见；新增分类为管理端能力，已迁移至
 * {@code AdminProductController}（POST /api/admin/product/category）。</p>
 */
@RestController
@RequestMapping("/api/product/category")
@RequiredArgsConstructor
public class CategoryController {

    private final CatalogService catalogService;

    /**
     * 分类列表。
     */
    @GetMapping("/list")
    public R<List<Category>> list() {
        return R.ok(catalogService.listCategories());
    }
}
