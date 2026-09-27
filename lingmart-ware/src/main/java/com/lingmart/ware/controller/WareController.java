package com.lingmart.ware.controller;

import com.lingmart.common.dto.WareStockInfo;
import com.lingmart.common.result.R;
import com.lingmart.ware.service.WareService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 仓储对外接口（经网关，需登录）。
 */
@RestController
@RequestMapping("/api/ware")
@RequiredArgsConstructor
public class WareController {

    private final WareService wareService;

    // 遗留调试端点，安全下线（注释保留便于回滚/二次开发）：
    // GET /api/ware/stock/{skuId} —— 任意登录用户可枚举 skuId 读取任意店铺库存（含内部 lockedStock），
    // 且全仓库前端无任何调用方，与内部批量接口 /internal/ware/stock/batch 重复。
    // 需要恢复时：取消注释并在对外可见性上做「仅店主可查」归属校验（经 shop /internal/shop/by-user 比对 shopId）。
    // @GetMapping("/stock/{skuId}")
    // public R<WareStockInfo> getStock(@PathVariable("skuId") Long skuId) {
    //     return R.ok(wareService.getStock(skuId));
    // }
}
