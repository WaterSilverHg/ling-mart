package com.lingmart.shop.service;

import com.lingmart.common.constant.AuthConstants;
import com.lingmart.common.dto.UserInfo;
import com.lingmart.common.result.R;
import com.lingmart.shop.entity.Shop;
import com.lingmart.shop.feign.AuthFeignClient;
import com.lingmart.shop.mapper.ShopMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 角色提升自动补偿任务单元测试。
 *
 * <p>覆盖：店主角色已是卖家则跳过；缺失/不符则自动补发；auth 调用异常时静默降级不影响其他店铺。</p>
 */
@ExtendWith(MockitoExtension.class)
class RolePromotionRetrySchedulerTest {

    @Mock
    private ShopMapper shopMapper;
    @Mock
    private AuthFeignClient authFeignClient;

    @InjectMocks
    private RolePromotionRetryScheduler scheduler;

    private Shop enabledShop(Long userId) {
        Shop shop = new Shop();
        shop.setId(1L);
        shop.setUserId(userId);
        shop.setStatus(1); // 营业中
        return shop;
    }

    private UserInfo user(Long id, String role) {
        UserInfo u = new UserInfo();
        u.setId(id);
        u.setRole(role);
        return u;
    }

    /** 角色已是卖家：跳过，不重复提升 */
    @Test
    void shouldSkipWhenRoleAlreadySeller() {
        when(shopMapper.selectList(any())).thenReturn(List.of(enabledShop(1L)));
        when(authFeignClient.getUser(1L)).thenReturn(R.ok(user(1L, AuthConstants.ROLE_SELLER)));

        scheduler.retryRolePromotion();

        verify(authFeignClient, never()).updateRole(any());
    }

    /** 角色缺失（仍是普通用户）：自动补发卖家角色 */
    @Test
    void shouldPromoteMissingRole() {
        when(shopMapper.selectList(any())).thenReturn(List.of(enabledShop(1L)));
        when(authFeignClient.getUser(1L)).thenReturn(R.ok(user(1L, AuthConstants.ROLE_USER)));

        scheduler.retryRolePromotion();

        verify(authFeignClient).updateRole(any());
    }

    /** auth 异常：捕获并记日志，不影响调度继续（下轮重试） */
    @Test
    void shouldTolerateAuthFailure() {
        when(shopMapper.selectList(any())).thenReturn(List.of(enabledShop(1L), enabledShop(2L)));
        when(authFeignClient.getUser(1L)).thenThrow(new RuntimeException("auth 不可用"));
        when(authFeignClient.getUser(2L)).thenReturn(R.ok(user(2L, AuthConstants.ROLE_USER)));

        assertDoesNotThrow(() -> scheduler.retryRolePromotion());
        verify(authFeignClient).updateRole(any());
    }
}
