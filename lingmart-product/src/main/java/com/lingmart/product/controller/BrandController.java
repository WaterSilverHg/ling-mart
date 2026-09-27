package com.lingmart.product.controller;

import com.lingmart.common.result.R;
import com.lingmart.product.entity.Brand;
import com.lingmart.product.service.CatalogService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 品牌接口（公开浏览）。
 *
 * <p>品牌列表已登录用户可见；新增品牌为管理端能力，已迁移至
 * {@code AdminProductController}（POST /api/admin/product/brand）。</p>
 */
@RestController
@RequestMapping("/api/product/brand")
@RequiredArgsConstructor
public class BrandController {

    private final CatalogService catalogService;

    /**
     * 品牌列表。
     */
    @GetMapping("/list")
    public R<List<Brand>> list() {
        return R.ok(catalogService.listBrands());
    }
}
