package com.lingmart.shop.controller;

import com.lingmart.common.constant.AuthConstants;
import com.lingmart.common.dto.ShopInfo;
import com.lingmart.common.result.R;
import com.lingmart.shop.dto.ApplyShopDTO;
import com.lingmart.shop.service.ShopService;
import com.lingmart.shop.vo.ApplicationVO;
import com.lingmart.shop.vo.ShopStorefrontVO;
import com.lingmart.shop.vo.ShopVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 店铺服务接口。
 *
 * <p>用户身份由网关透传的 {@code X-User-Id} / {@code X-User-Role} 请求头提供。
 * 审核相关接口仅管理员可调用。</p>
 */
@RestController
@RequestMapping("/api/shop")
@RequiredArgsConstructor
public class ShopController {

    private final ShopService shopService;

    /**
     * 提交入驻申请。
     */
    @PostMapping("/application")
    public R<Void> apply(@Valid @RequestBody ApplyShopDTO dto,
                         @RequestHeader(value = AuthConstants.HEADER_USER_ID, required = false) Long userId) {
        shopService.apply(userId, dto);
        return R.ok();
    }

    /**
     * 查询我的入驻申请状态。
     */
    @GetMapping("/application/my")
    public R<ApplicationVO> myApplication(
            @RequestHeader(value = AuthConstants.HEADER_USER_ID, required = false) Long userId) {
        return R.ok(shopService.getMyApplication(userId));
    }

    /**
     * 查询我的店铺信息。
     */
    @GetMapping("/my")
    public R<ShopVO> myShop(
            @RequestHeader(value = AuthConstants.HEADER_USER_ID, required = false) Long userId) {
        return R.ok(shopService.getMyShop(userId));
    }

    /**
     * 更新我的店铺信息（仅已启用店铺可编辑）。
     */
    @PutMapping("/my")
    public R<Void> updateMyShop(@Valid @RequestBody ApplyShopDTO dto,
                                @RequestHeader(value = AuthConstants.HEADER_USER_ID, required = false) Long userId) {
        shopService.updateMyShop(userId, dto);
        return R.ok();
    }

    /**
     * 店铺主页（公开浏览）：店铺信息 + 店主公开信息 + 在售商品。无需登录（网关白名单放行）。
     */
    @GetMapping("/storefront/{shopId}")
    public R<ShopStorefrontVO> storefront(@PathVariable Long shopId) {
        return R.ok(shopService.getStorefront(shopId));
    }

    /**
     * 营业中店铺列表（公开浏览，首页以店铺为入口）。无需登录（网关白名单放行）。
     */
    @GetMapping("/list")
    public R<List<ShopInfo>> listEnabledShops() {
        return R.ok(shopService.listEnabledShops());
    }

    /**
     * 管理端接口（审核/审计日志）已迁移至 {@link AdminShopController}（/api/admin/shop/**）。
     */
}
