package com.lingmart.product.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.lingmart.common.constant.AuthConstants;
import com.lingmart.common.util.AuthUtils;
import com.lingmart.product.dto.BrandDTO;
import com.lingmart.product.dto.CategoryDTO;
import com.lingmart.product.entity.Brand;
import com.lingmart.product.entity.Category;
import com.lingmart.product.mapper.BrandMapper;
import com.lingmart.product.mapper.CategoryMapper;
import com.lingmart.product.service.CatalogService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 商品目录服务实现。
 *
 * <p>列表对已登录用户开放，走 Redis 缓存（10 分钟 TTL，管理员新增后最迟 10 分钟可见）；
 * 新增操作仅管理员可执行（角色来自网关透传的 X-User-Role）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CatalogServiceImpl implements CatalogService {

    private final CategoryMapper categoryMapper;
    private final BrandMapper brandMapper;

    @Override
    @Cacheable(cacheNames = "catalog", key = "'categories'")
    public List<Category> listCategories() {
        return categoryMapper.selectList(Wrappers.<Category>lambdaQuery()
                .eq(Category::getStatus, 1)
                .orderByAsc(Category::getSort));
    }

    @Override
    public void createCategory(String role, CategoryDTO dto) {
        AuthUtils.requireRole(role, AuthConstants.ROLE_ADMIN);
        Category category = new Category();
        category.setName(dto.getName());
        category.setParentId(dto.getParentId() == null ? 0L : dto.getParentId());
        category.setSort(dto.getSort() == null ? 0 : dto.getSort());
        category.setStatus(1);
        categoryMapper.insert(category);
        log.info("新增分类：name={}", dto.getName());
    }

    @Override
    @Cacheable(cacheNames = "catalog", key = "'brands'")
    public List<Brand> listBrands() {
        return brandMapper.selectList(Wrappers.<Brand>lambdaQuery()
                .eq(Brand::getStatus, 1)
                .orderByAsc(Brand::getId));
    }

    @Override
    public void createBrand(String role, BrandDTO dto) {
        AuthUtils.requireRole(role, AuthConstants.ROLE_ADMIN);
        Brand brand = new Brand();
        brand.setName(dto.getName());
        brand.setLogo(dto.getLogo());
        brand.setStatus(1);
        brandMapper.insert(brand);
        log.info("新增品牌：name={}", dto.getName());
    }
}
