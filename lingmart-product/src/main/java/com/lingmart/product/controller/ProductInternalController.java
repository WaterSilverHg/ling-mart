package com.lingmart.product.controller;

import com.lingmart.common.dto.SaleItem;
import com.lingmart.common.dto.ShopProductItem;
import com.lingmart.common.dto.SkuInfoDTO;
import com.lingmart.common.dto.SpuDTO;
import com.lingmart.common.exception.BizException;
import com.lingmart.common.result.R;
import com.lingmart.product.entity.Sku;
import com.lingmart.product.entity.SkuImage;
import com.lingmart.product.entity.Spu;
import com.lingmart.product.mapper.SkuImageMapper;
import com.lingmart.product.mapper.SkuMapper;
import com.lingmart.product.mapper.SpuMapper;
import com.lingmart.product.service.ProductService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 商品内部接口（Feign 专用，不经过网关）。
 *
 * <p>供订单服务下单时获取 SKU 快照（标题/价格/店铺），避免订单服务直连商品库。</p>
 */
@RestController
@RequestMapping("/internal/product")
@RequiredArgsConstructor
public class ProductInternalController {

    private final SpuMapper spuMapper;
    private final SkuMapper skuMapper;
    private final SkuImageMapper skuImageMapper;
    private final ProductService productService;

    /**
     * 按 SKU ID 获取商品快照（含 SPU 标题与店铺归属）。
     *
     * @param skuId SKU ID
     * @return 商品快照
     */
    @GetMapping("/sku/{skuId}")
    public R<SkuInfoDTO> getSkuInfo(@PathVariable("skuId") Long skuId) {
        Sku sku = skuMapper.selectById(skuId);
        if (sku == null) {
            throw new BizException("SKU 不存在");
        }
        Spu spu = spuMapper.selectById(sku.getSpuId());
        // 仅审核通过且在售的商品可被下单（防未审核/已下架商品流入订单）
        if (spu == null || !Integer.valueOf(1).equals(spu.getStatus())
                || !Integer.valueOf(1).equals(spu.getReviewStatus())) {
            throw new BizException("商品未上架或未通过审核，无法购买");
        }

        SkuInfoDTO dto = new SkuInfoDTO();
        dto.setSpuId(sku.getSpuId());
        dto.setSkuId(sku.getId());
        dto.setShopId(sku.getShopId());
        dto.setSpuTitle(spu == null ? null : spu.getTitle());
        dto.setSkuTitle(sku.getTitle());
        dto.setPrice(sku.getPrice());
        dto.setImage(skuImageMapper.selectList(new LambdaQueryWrapper<SkuImage>()
                        .eq(SkuImage::getSkuId, skuId).orderByAsc(SkuImage::getSort).last("LIMIT 1"))
                .stream().findFirst().map(SkuImage::getImgUrl).orElse(null));
        return R.ok(dto);
    }

    /**
     * 开店申请携带的初始商品：批量创建为待审核商品（shop 服务申请时调用）。
     *
     * @param shopId        店铺ID
     * @param applicationId 入驻申请ID
     * @param products      商品清单
     * @return 创建的 SPU ID 列表
     */
    @PostMapping("/batch")
    public R<List<Long>> createForApplication(@RequestParam Long shopId,
                                              @RequestParam Long applicationId,
                                              @RequestBody List<SpuDTO> products) {
        return R.ok(productService.createProductsForApplication(shopId, applicationId, products));
    }

    /**
     * 入驻申请审核通过：申请关联的商品全部通过并上架（shop 服务调用）。
     *
     * @param applicationId 入驻申请ID
     * @param shopName      店铺名称（供 ES 索引展示，可空）
     */
    @PostMapping("/application/{id}/approve")
    public R<Void> approveByApplication(@PathVariable("id") Long applicationId,
                                        @RequestParam(value = "shopName", required = false) String shopName) {
        productService.approveByApplication(applicationId, shopName);
        return R.ok();
    }

    /**
     * 入驻申请审核拒绝：申请关联的商品全部拒绝（shop 服务调用）。
     */
    @PostMapping("/application/{id}/reject")
    public R<Void> rejectByApplication(@PathVariable("id") Long applicationId) {
        productService.rejectByApplication(applicationId);
        return R.ok();
    }

    /**
     * 累加销量（支付成功后 order 服务调用）。
     */
    @PostMapping("/sale")
    public R<Void> updateSale(@RequestBody List<SaleItem> items) {
        productService.updateSale(items);
        return R.ok();
    }

    /**
     * 回退销量（退款审核通过后 order 服务调用）。
     *
     * <p>与 {@code /sale} 语义分离：累加只增不减，回退用 {@code GREATEST(sale_count - n, 0)}
     * 保证不出现负销量，并同样异步刷新 ES 销量。</p>
     */
    @PostMapping("/sale/rollback")
    public R<Void> rollbackSale(@RequestParam(value = "orderSn", required = false) String orderSn,
                                @RequestBody List<SaleItem> items) {
        productService.rollbackSale(orderSn, items);
        return R.ok();
    }

    /**
     * 重建某店铺在售商品的 ES 文档（店铺改名审核通过后 shop 服务调用，店名为新店名）。
     *
     * @return 实际重建的文档数
     */
    @PostMapping("/shop/{shopId}/reindex-es")
    public R<Integer> reindexShopEs(@PathVariable("shopId") Long shopId,
                                    @RequestParam("shopName") String shopName) {
        return R.ok(productService.refreshEsByShop(shopId, shopName));
    }

    /**
     * 某店铺的在售商品（审核通过 + 上架，店铺主页展示；shop 服务调用）。
     */
    @GetMapping("/shop/{shopId}")
    public R<List<ShopProductItem>> onShelfByShop(@PathVariable("shopId") Long shopId) {
        return R.ok(productService.listOnShelfByShop(shopId));
    }
}
