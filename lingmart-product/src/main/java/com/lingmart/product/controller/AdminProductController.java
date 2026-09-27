package com.lingmart.product.controller;

import com.lingmart.common.constant.AuthConstants;
import com.lingmart.common.result.PageResult;
import com.lingmart.common.result.R;
import com.lingmart.common.util.AuthUtils;
import com.lingmart.product.dto.BrandDTO;
import com.lingmart.product.dto.CategoryDTO;
import com.lingmart.product.dto.ProductReviewBatchRequest;
import com.lingmart.product.dto.ProductReviewRequest;
import com.lingmart.product.entity.AuditLog;
import com.lingmart.product.service.CatalogService;
import com.lingmart.product.service.ProductService;
import com.lingmart.product.vo.ProductReviewVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 商品服务 · 管理端接口（独立于普通用户/卖家接口）。
 *
 * <p>路径统一挂载 {@code /api/admin/product/**}，由网关做角色白名单拦截（仅 ROLE_ADMIN 可访问），
 * 服务层 {@link AuthUtils#requireRole} 保留作为兜底。仅提供审核、审计日志与分类/品牌维护能力，
 * 与普通用户的商品浏览/卖家操作（{@link ProductController}）彻底分离。</p>
 */
@RestController
@RequestMapping("/api/admin/product")
@RequiredArgsConstructor
public class AdminProductController {

    private final ProductService productService;
    private final CatalogService catalogService;

    /**
     * 待审商品列表（status 缺省=0待审核，传 3 查看待重审；分页）。
     */
    @GetMapping("/review/list")
    public R<PageResult<ProductReviewVO>> reviewList(
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false, defaultValue = "1") long page,
            @RequestParam(required = false, defaultValue = "10") long size,
            @RequestHeader(value = AuthConstants.HEADER_USER_ROLE, required = false) String role) {
        AuthUtils.requireRole(role, AuthConstants.ROLE_ADMIN);
        return R.ok(productService.reviewList(status, page, size));
    }

    /**
     * 审核单个商品（通过 / 拒绝），结果入审计。
     */
    @PostMapping("/review/{id}")
    public R<Void> review(@PathVariable Long id,
                          @Valid @RequestBody ProductReviewRequest request,
                          @RequestHeader(value = AuthConstants.HEADER_USER_ID, required = false) Long userId,
                          @RequestHeader(value = AuthConstants.HEADER_USER_ROLE, required = false) String role) {
        AuthUtils.requireRole(role, AuthConstants.ROLE_ADMIN);
        productService.reviewProduct(userId, id, Boolean.TRUE.equals(request.getApprove()), request.getRejectReason());
        return R.ok();
    }

    /**
     * 重建某店铺在售商品的 ES 文档（人工兜底：改名时刷新失败、历史脏数据导致店名/销量与 DB 漂移）。
     *
     * <p>店名由 product 经 shop 服务新鲜查询（不走 5 分钟本地缓存）。</p>
     *
     * @return 实际重建的文档数
     */
    @PostMapping("/shop/{shopId}/reindex-es")
    public R<Integer> reindexShopEs(@PathVariable Long shopId,
                                    @RequestHeader(value = AuthConstants.HEADER_USER_ROLE, required = false) String role) {
        AuthUtils.requireRole(role, AuthConstants.ROLE_ADMIN);
        return R.ok(productService.refreshEsByShop(shopId, null));
    }

    /**
     * 批量审核商品（通过 / 拒绝），逐条入审计。
     */
    @PostMapping("/review/batch")
    public R<Void> reviewBatch(@Valid @RequestBody ProductReviewBatchRequest request,
                               @RequestHeader(value = AuthConstants.HEADER_USER_ID, required = false) Long userId,
                               @RequestHeader(value = AuthConstants.HEADER_USER_ROLE, required = false) String role) {
        AuthUtils.requireRole(role, AuthConstants.ROLE_ADMIN);
        productService.reviewProducts(userId, request.getSpuIds(),
                Boolean.TRUE.equals(request.getApprove()), request.getRejectReason());
        return R.ok();
    }

    /**
     * 按入驻申请查询其携带的商品（审核申请时展示，含审核状态）。
     */
    @GetMapping("/review/by-application/{applicationId}")
    public R<List<ProductReviewVO>> reviewByApplication(@PathVariable Long applicationId,
                                                        @RequestHeader(value = AuthConstants.HEADER_USER_ROLE, required = false) String role) {
        AuthUtils.requireRole(role, AuthConstants.ROLE_ADMIN);
        return R.ok(productService.reviewListByApplication(applicationId));
    }

    /**
     * 审核审计日志列表（分页）。
     */
    @GetMapping("/audit/list")
    public R<PageResult<AuditLog>> auditList(
            @RequestParam(required = false, defaultValue = "1") long page,
            @RequestParam(required = false, defaultValue = "10") long size,
            @RequestHeader(value = AuthConstants.HEADER_USER_ROLE, required = false) String role) {
        return R.ok(productService.auditList(role, page, size));
    }

    /**
     * 新增分类（管理员）。
     */
    @PostMapping("/category")
    public R<Void> createCategory(@Valid @RequestBody CategoryDTO dto,
                                  @RequestHeader(value = AuthConstants.HEADER_USER_ROLE, required = false) String role) {
        catalogService.createCategory(role, dto);
        return R.ok();
    }

    /**
     * 新增品牌（管理员）。
     */
    @PostMapping("/brand")
    public R<Void> createBrand(@Valid @RequestBody BrandDTO dto,
                               @RequestHeader(value = AuthConstants.HEADER_USER_ROLE, required = false) String role) {
        catalogService.createBrand(role, dto);
        return R.ok();
    }
}
