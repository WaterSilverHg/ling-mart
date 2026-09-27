package com.lingmart.order.feign;

import com.lingmart.common.dto.SaleItem;
import com.lingmart.common.dto.SkuInfoDTO;
import com.lingmart.common.result.R;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

/**
 * 商品服务 Feign 客户端：下单取 SKU 快照、支付后累加销量。
 */
@FeignClient(name = "lingmart-product")
public interface ProductFeignClient {

    @GetMapping("/internal/product/sku/{skuId}")
    R<SkuInfoDTO> getSkuInfo(@PathVariable("skuId") Long skuId);

    @PostMapping("/internal/product/sale")
    R<Void> updateSale(@RequestBody List<SaleItem> items);

    /**
     * 退款审核通过：回退销量（与 {@link #updateSale} 语义分离，product 侧用
     * {@code GREATEST(sale_count - n, 0)} 保证不会减成负数，并同样异步刷新 ES 销量）。
     *
     * @param orderSn 订单号（product 侧以它为幂等键，同一订单只回退一次）
     * @param items   销量条目（num 为正，product 侧取绝对值作为回退量）
     */
    @PostMapping("/internal/product/sale/rollback")
    R<Void> rollbackSale(@RequestParam("orderSn") String orderSn, @RequestBody List<SaleItem> items);
}
