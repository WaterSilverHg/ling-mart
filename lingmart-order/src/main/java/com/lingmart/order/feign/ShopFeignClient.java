package com.lingmart.order.feign;

import com.lingmart.common.dto.ShopInfo;
import com.lingmart.common.result.R;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/**
 * 店铺服务 Feign 客户端：卖家发货 / 卖家订单列表时按用户取店铺归属。
 */
@FeignClient(name = "lingmart-shop")
public interface ShopFeignClient {

    @GetMapping("/internal/shop/by-user/{userId}")
    R<ShopInfo> getShopByUserId(@PathVariable("userId") Long userId);
}
