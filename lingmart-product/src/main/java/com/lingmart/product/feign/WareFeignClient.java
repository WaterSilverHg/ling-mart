package com.lingmart.product.feign;

import com.lingmart.common.dto.StockSyncItem;
import com.lingmart.common.dto.WareStockInfo;
import com.lingmart.common.result.R;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.List;

/**
 * 仓储服务 Feign 客户端：卖家发布/更新商品时同步 SKU 库存；详情/列表回源实时可售余量。
 */
@FeignClient(name = "lingmart-ware")
public interface WareFeignClient {

    /**
     * 同步 SKU 库存到仓储服务。
     *
     * @param items 库存条目
     * @return 统一结果
     */
    @PostMapping("/internal/ware/sku/sync")
    R<Void> syncStock(@RequestBody List<StockSyncItem> items);

    /**
     * 批量查询 SKU 库存快照（买家详情/卖家列表展示「剩余件数」）。
     *
     * @param skuIds SKU ID 列表
     * @return 库存信息列表
     */
    @PostMapping("/internal/ware/stock/batch")
    R<List<WareStockInfo>> listStock(@RequestBody List<Long> skuIds);
}
