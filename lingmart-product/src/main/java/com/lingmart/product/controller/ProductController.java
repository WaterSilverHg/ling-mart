package com.lingmart.product.controller;

import com.lingmart.common.constant.AuthConstants;
import com.lingmart.common.dto.SkuDTO;
import com.lingmart.common.dto.SpuDTO;
import com.lingmart.common.exception.BizException;
import com.lingmart.common.result.PageResult;
import com.lingmart.common.result.R;
import com.lingmart.product.service.ProductService;
import com.lingmart.product.vo.ProductDetailVO;
import com.lingmart.product.vo.ProductVO;
import com.lingmart.product.vo.SkuDetailVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 商品接口。
 *
 * <p>卖家操作（发布/编辑/上下架）通过网关透传的 {@code X-User-Id} 识别用户，
 * 服务内部经 Feign 获取其店铺并校验商品归属（数据隔离）。
 * 管理端审核/审计接口已迁移至 {@link AdminProductController}（/api/admin/product/**）。</p>
 */
@RestController
@RequestMapping("/api/product")
@RequiredArgsConstructor
public class ProductController {

    private final ProductService productService;

    /**
     * 发布商品（SPU + SKU，进入待审核，未上架）。
     */
    @PostMapping
    public R<Long> create(@Valid @RequestBody SpuDTO dto,
                          @RequestHeader(value = AuthConstants.HEADER_USER_ID, required = false) Long userId) {
        return R.ok(productService.createProduct(userId, dto));
    }

    /**
     * 编辑商品内容（宽松重审：新内容进草稿，旧内容照常销售，审核通过后才生效）。
     */
    @PutMapping("/{id}")
    public R<Void> update(@PathVariable Long id,
                          @Valid @RequestBody SpuDTO dto,
                          @RequestHeader(value = AuthConstants.HEADER_USER_ID, required = false) Long userId) {
        productService.updateProduct(userId, id, dto);
        return R.ok();
    }

    /**
     * 仅修改库存（不触发重审）。
     */
    @PutMapping("/{id}/stock")
    public R<Void> updateStock(@PathVariable Long id,
                               @RequestBody List<SkuDTO> skus,
                               @RequestHeader(value = AuthConstants.HEADER_USER_ID, required = false) Long userId) {
        productService.updateStock(userId, id, skus);
        return R.ok();
    }

    /**
     * 上架商品（仅审核通过的商品可上架）。
     */
    @PostMapping("/{id}/onshelf")
    public R<Void> onShelf(@PathVariable Long id,
                           @RequestHeader(value = AuthConstants.HEADER_USER_ID, required = false) Long userId) {
        productService.onShelf(userId, id);
        return R.ok();
    }

    /**
     * 下架商品（从 ES 删除）。
     */
    @PostMapping("/{id}/offshelf")
    public R<Void> offShelf(@PathVariable Long id,
                            @RequestHeader(value = AuthConstants.HEADER_USER_ID, required = false) Long userId) {
        productService.offShelf(userId, id);
        return R.ok();
    }

    /**
     * 商品详情（对外查看，带可见性门禁：仅“上架且审核通过”对所有人可见，非公开商品仅属主可预览；
     * 不可见与“商品不存在”同语义，防枚举未发布商品）。
     */
    @GetMapping("/{id}")
    public R<ProductDetailVO> detail(@PathVariable Long id,
                                     @RequestHeader(value = AuthConstants.HEADER_USER_ID, required = false) Long userId) {
        ProductDetailVO vo = productService.detailForView(id, userId);
        if (vo == null) {
            throw new BizException("商品不存在");
        }
        return R.ok(vo);
    }

    /**
     * 商品 SKU 分页（详情页内嵌 SKU 超上限时按此接口加载；可见性门禁同详情）。
     */
    @GetMapping("/{id}/skus")
    public R<PageResult<SkuDetailVO>> skus(@PathVariable Long id,
                                           @RequestHeader(value = AuthConstants.HEADER_USER_ID, required = false) Long userId,
                                           @RequestParam(required = false, defaultValue = "1") long page,
                                           @RequestParam(required = false, defaultValue = "20") long size) {
        return R.ok(productService.pageSkusForView(id, userId, page, size));
    }

    /**
     * 我的商品列表（当前用户店铺的商品，分页）。
     *
     * @param status       可选，按上下架状态筛选：0 已下架，1 已上架；不传=全部
     * @param reviewStatus 可选，按审核状态筛选：0 待审核，1 已通过，2 已拒绝，3 待重审；不传=全部
     */
    @GetMapping("/my")
    public R<PageResult<ProductVO>> my(
            @RequestParam(required = false, defaultValue = "1") long page,
            @RequestParam(required = false, defaultValue = "10") long size,
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false) Integer reviewStatus,
            @RequestHeader(value = AuthConstants.HEADER_USER_ID, required = false) Long userId) {
        return R.ok(productService.myProducts(userId, page, size, status, reviewStatus));
    }

    /**
     * 已上架商品列表（公开浏览，分页）。
     */
    @GetMapping("/list")
    public R<PageResult<ProductVO>> list(
            @RequestParam(required = false, defaultValue = "1") long page,
            @RequestParam(required = false, defaultValue = "10") long size) {
        return R.ok(productService.listOnShelf(page, size));
    }
}
