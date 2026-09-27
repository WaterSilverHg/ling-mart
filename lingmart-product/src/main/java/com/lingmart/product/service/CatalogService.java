package com.lingmart.product.service;

import com.lingmart.product.dto.BrandDTO;
import com.lingmart.product.dto.CategoryDTO;
import com.lingmart.product.entity.Brand;
import com.lingmart.product.entity.Category;

import java.util.List;

/**
 * 商品目录服务接口（分类 + 品牌）。
 */
public interface CatalogService {

    /**
     * 分类列表（仅启用的分类，按排序）。
     *
     * @return 分类列表
     */
    List<Category> listCategories();

    /**
     * 新增分类（管理员）。
     *
     * @param role 当前用户角色（须为 ROLE_ADMIN）
     * @param dto  分类参数
     */
    void createCategory(String role, CategoryDTO dto);

    /**
     * 品牌列表（仅启用的品牌）。
     *
     * @return 品牌列表
     */
    List<Brand> listBrands();

    /**
     * 新增品牌（管理员）。
     *
     * @param role 当前用户角色（须为 ROLE_ADMIN）
     * @param dto  品牌参数
     */
    void createBrand(String role, BrandDTO dto);
}
