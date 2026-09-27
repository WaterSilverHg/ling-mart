package com.lingmart.shop.controller;

import com.lingmart.common.constant.AuthConstants;
import com.lingmart.common.result.PageResult;
import com.lingmart.common.result.R;
import com.lingmart.shop.dto.ReviewApplicationDTO;
import com.lingmart.shop.entity.AuditLog;
import com.lingmart.shop.service.ShopService;
import com.lingmart.shop.vo.ApplicationVO;
import com.lingmart.shop.vo.ShopVO;
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

/**
 * 店铺服务 · 管理端接口（独立于普通用户接口）。
 *
 * <p>路径统一挂载 {@code /api/admin/shop/**}，由网关做角色白名单拦截（仅 ROLE_ADMIN 可访问），
 * 服务层 {@code AuthUtils.requireRole} 保留作为兜底。仅提供审核与审计日志能力，
 * 与普通用户的申请/店铺操作（{@link ShopController}）彻底分离。</p>
 */
@RestController
@RequestMapping("/api/admin/shop")
@RequiredArgsConstructor
public class AdminShopController {

    private final ShopService shopService;

    /**
     * 入驻申请列表（可按状态过滤，分页）。
     */
    @GetMapping("/applications")
    public R<PageResult<ApplicationVO>> listApplications(
            @RequestHeader(value = AuthConstants.HEADER_USER_ROLE, required = false) String role,
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false, defaultValue = "1") long page,
            @RequestParam(required = false, defaultValue = "10") long size) {
        return R.ok(shopService.listApplications(role, status, page, size));
    }

    /**
     * 审核入驻申请：通过则启用店铺、商品通过并上架、提升用户为卖家；结果入审计。
     */
    @PostMapping("/applications/{id}/review")
    public R<Void> review(@PathVariable Long id,
                          @Valid @RequestBody ReviewApplicationDTO dto,
                          @RequestHeader(value = AuthConstants.HEADER_USER_ID, required = false) Long userId,
                          @RequestHeader(value = AuthConstants.HEADER_USER_ROLE, required = false) String role) {
        shopService.review(role, userId, id, dto);
        return R.ok();
    }

    /**
     * 店铺列表（按审核状态过滤；用于店铺信息变更重审，分页）。
     */
    @GetMapping("/shops")
    public R<PageResult<ShopVO>> listShops(
            @RequestHeader(value = AuthConstants.HEADER_USER_ROLE, required = false) String role,
            @RequestParam(required = false) Integer reviewStatus,
            @RequestParam(required = false, defaultValue = "1") long page,
            @RequestParam(required = false, defaultValue = "10") long size) {
        return R.ok(shopService.listShops(role, reviewStatus, page, size));
    }

    /**
     * 审核店铺信息变更（宽松重审）：通过则应用新信息，拒绝则丢弃草稿；结果入审计。
     */
    @PostMapping("/shops/{shopId}/review")
    public R<Void> reviewShop(@PathVariable Long shopId,
                              @Valid @RequestBody ReviewApplicationDTO dto,
                              @RequestHeader(value = AuthConstants.HEADER_USER_ID, required = false) Long userId,
                              @RequestHeader(value = AuthConstants.HEADER_USER_ROLE, required = false) String role) {
        shopService.reviewShop(role, userId, shopId, dto);
        return R.ok();
    }

    /**
     * 审核审计日志列表（分页）。
     */
    @GetMapping("/audit/list")
    public R<PageResult<AuditLog>> auditList(
            @RequestHeader(value = AuthConstants.HEADER_USER_ROLE, required = false) String role,
            @RequestParam(required = false, defaultValue = "1") long page,
            @RequestParam(required = false, defaultValue = "10") long size) {
        return R.ok(shopService.auditList(role, page, size));
    }
}
