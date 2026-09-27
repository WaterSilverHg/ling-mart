package com.lingmart.chat.feign;

import com.lingmart.common.dto.ShopInfo;
import com.lingmart.common.result.R;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.List;

/**
 * 店铺服务 Feign 客户端：发起会话前校验店铺并获取店主信息。
 */
@FeignClient(name = "lingmart-shop")
public interface ShopFeignClient {

    /**
     * 按店铺 ID 查询店铺信息（校验店铺营业状态、获取店主用户 ID）。
     *
     * @param shopId 店铺 ID
     * @return 店铺信息；不存在时 data 为 null
     */
    @GetMapping("/internal/shop/{shopId}")
    R<ShopInfo> getShopById(@PathVariable("shopId") Long shopId);

    /**
     * 按店铺 ID 集合批量查询店铺信息（会话列表展示店铺名/logo 等，避免逐个 Feign 的 N+1）。
     *
     * @param shopIds 店铺 ID 列表
     * @return 店铺信息列表（data 为 List，可能为空；未命中的 id 不占位）
     */
    @PostMapping("/internal/shop/batch")
    R<List<ShopInfo>> getShopsByIds(@RequestBody List<Long> shopIds);
}
