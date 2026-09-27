package com.lingmart.shop.controller;

import com.lingmart.common.dto.ShopInfo;
import com.lingmart.common.result.R;
import com.lingmart.shop.service.ShopService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 店铺服务内部接口（供其他服务 Feign 调用，不经过网关）。
 */
@RestController
@RequestMapping("/internal/shop")
@RequiredArgsConstructor
public class ShopInternalController {

    private final ShopService shopService;

    /**
     * 按用户 ID 查询店铺信息（商品服务创建商品时获取当前用户的店铺）。
     *
     * @param userId 用户 ID
     * @return 店铺信息；无店铺时 data 为 null
     */
    @GetMapping("/by-user/{userId}")
    public R<ShopInfo> getShopByUserId(@PathVariable Long userId) {
        return R.ok(shopService.getShopByUserId(userId));
    }

    /**
     * 按店铺 ID 查询店铺信息（商品服务展示店铺名等）。
     *
     * @param shopId 店铺 ID
     * @return 店铺信息；不存在时 data 为 null
     */
    @GetMapping("/{shopId}")
    public R<ShopInfo> getShopById(@PathVariable Long shopId) {
        return R.ok(shopService.getShopById(shopId));
    }

    /**
     * 按店铺 ID 集合批量查询店铺信息（聊天会话列表等批量聚合场景，避免逐个 Feign 的 N+1）。
     *
     * @param shopIds 店铺 ID 列表
     * @return 店铺信息列表（data 为 List，可能为空；未命中的 id 不占位）
     */
    @PostMapping("/batch")
    public R<List<ShopInfo>> getShopsByIds(@RequestBody List<Long> shopIds) {
        return R.ok(shopService.getShopsByIds(shopIds));
    }
}
