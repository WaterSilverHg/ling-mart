package com.lingmart.shop.feign;

import com.lingmart.common.dto.ShopProductItem;
import com.lingmart.common.dto.SpuDTO;
import com.lingmart.common.result.R;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

/**
 * 商品服务 Feign 客户端：开店申请携带初始商品、审核联动商品状态。
 */
@FeignClient(name = "lingmart-product")
public interface ProductFeignClient {

    /**
     * 开店申请：批量创建初始商品（待审核）。
     *
     * @param shopId        店铺ID
     * @param applicationId 入驻申请ID
     * @param products      商品清单
     * @return 创建的 SPU ID 列表
     */
    @PostMapping("/internal/product/batch")
    R<List<Long>> createForApplication(@RequestParam Long shopId, @RequestParam Long applicationId,
                                       @RequestBody List<SpuDTO> products);

    /**
     * 申请审核通过：关联商品全部通过并上架。
     *
     * <p>传入 shopName 供商品服务写入 ES 索引，避免其在全局事务内回调本服务。</p>
     *
     * @param applicationId 入驻申请ID
     * @param shopName      店铺名称（可空）
     */
    @PostMapping("/internal/product/application/{id}/approve")
    R<Void> approveByApplication(@PathVariable("id") Long applicationId,
                                 @RequestParam("shopName") String shopName);

    /**
     * 申请审核拒绝：关联商品全部拒绝。
     */
    @PostMapping("/internal/product/application/{id}/reject")
    R<Void> rejectByApplication(@PathVariable("id") Long applicationId);

    /**
     * 某店铺的在售商品（审核通过 + 上架，店铺主页展示）。
     *
     * @param shopId 店铺 ID
     * @return 在售商品条目列表
     */
    @GetMapping("/internal/product/shop/{shopId}")
    R<List<ShopProductItem>> getShopProducts(@PathVariable("shopId") Long shopId);

    /**
     * 店铺改名审核通过：重建该店在售商品的 ES 文档（店名是 ES 里的快照，不同步会一直显示旧名）。
     *
     * <p>直接把新店名传给商品服务，避免其在本地 5 分钟店名缓存里取到旧值。</p>
     *
     * @param shopId   店铺 ID
     * @param shopName 新店铺名称
     * @return 实际重建的文档数
     */
    @PostMapping("/internal/product/shop/{shopId}/reindex-es")
    R<Integer> reindexShopEs(@PathVariable("shopId") Long shopId,
                             @RequestParam("shopName") String shopName);
}
