package com.lingmart.shop.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.lingmart.common.constant.AuthConstants;
import com.lingmart.common.dto.UserInfo;
import com.lingmart.common.dto.UserRoleUpdateRequest;
import com.lingmart.common.result.R;
import com.lingmart.shop.entity.Shop;
import com.lingmart.shop.feign.AuthFeignClient;
import com.lingmart.shop.mapper.ShopMapper;
import com.lingmart.shop.service.impl.ShopServiceImpl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 角色提升自动补偿定时任务。
 *
 * <p>入驻审核通过时 auth 角色提升未纳入 Seata（独立库无 undo_log），原实现仅记日志 + 手工修正入口；
 * 本任务周期扫描「营业中（审核已通过）店铺」的店主角色，若仍非 ROLE_SELLER 则自动补发，
 * 实现角色提升的最终一致。单批限 20 家避免对 auth 造成瞬时压力。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RolePromotionRetryScheduler {

    /** 单批扫描上限（防大促时集中补偿打爆 auth） */
    private static final int BATCH_LIMIT = 20;

    private final ShopMapper shopMapper;
    private final AuthFeignClient authFeignClient;

    /**
     * 周期补偿：营业中店铺的店主角色若未提升为卖家则补发。
     */
    @Scheduled(fixedDelayString = "${lingmart.shop.role-retry-ms:60000}")
    public void retryRolePromotion() {
        List<Shop> enabledShops = shopMapper.selectList(Wrappers.<Shop>lambdaQuery()
                .eq(Shop::getStatus, ShopServiceImpl.SHOP_ENABLED)
                .last("LIMIT " + BATCH_LIMIT));
        if (enabledShops.isEmpty()) {
            return;
        }
        int fixed = 0;
        for (Shop shop : enabledShops) {
            try {
                R<UserInfo> r = authFeignClient.getUser(shop.getUserId());
                UserInfo user = r == null ? null : r.getData();
                if (user != null && AuthConstants.ROLE_SELLER.equals(user.getRole())) {
                    continue; // 角色已正确，无需补偿
                }
                // 角色缺失/不符（审核时提升失败或漏更新）→ 自动补发
                UserRoleUpdateRequest req = new UserRoleUpdateRequest();
                req.setUserId(shop.getUserId());
                req.setRole(AuthConstants.ROLE_SELLER);
                authFeignClient.updateRole(req);
                fixed++;
                log.info("角色提升自动补偿：userId={}, shopId={}", shop.getUserId(), shop.getId());
            } catch (Exception e) {
                log.warn("角色补偿检查失败（下轮重试）：userId={}, err={}", shop.getUserId(), e.getMessage());
            }
        }
        if (fixed > 0) {
            log.info("角色提升自动补偿：修复 {} 个店主角色", fixed);
        }
    }
}
