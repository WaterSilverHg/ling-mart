package com.lingmart.product.feign;

import com.lingmart.common.dto.ShopInfo;
import com.lingmart.common.result.R;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.List;

/**
 * 店铺服务 Feign 客户端。
 *
 * <p>商品服务通过该客户端获取当前用户的店铺（数据隔离依据）：
 * 商品只能归属当前用户自己的店铺，店铺未启用则无法发布商品。</p>
 */
@FeignClient(name = "lingmart-shop")
public interface ShopFeignClient {

    /**
     * 按用户 ID 查询店铺信息。
     *
     * @param userId 用户 ID
     * @return 统一响应，data 为店铺信息（无店铺时为 null）
     */
    @GetMapping("/internal/shop/by-user/{userId}")
    R<ShopInfo> getShopByUserId(@PathVariable("userId") Long userId);

    /**
     * 按店铺 ID 查询店铺信息（展示店铺名等）。
     *
     * @param shopId 店铺 ID
     * @return 统一响应，data 为店铺信息
     */
    @GetMapping("/internal/shop/{shopId}")
    R<ShopInfo> getShopById(@PathVariable("shopId") Long shopId);

    /**
     * 批量按店铺 ID 查询店铺信息（审核列表等场景一次取回，避免逐行 Feign 的 N+1）。
     *
     * @param shopIds 店铺 ID 列表（调用方需去重）
     * @return 统一响应，data 为店铺信息列表（不存在的 ID 不在结果中）
     */
    @PostMapping("/internal/shop/batch")
    R<List<ShopInfo>> getShopsByIds(@RequestBody List<Long> shopIds);
}
