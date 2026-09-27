package com.lingmart.shop.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lingmart.common.constant.AuthConstants;
import com.lingmart.common.dto.UserRoleUpdateRequest;
import com.lingmart.common.exception.BizException;
import com.lingmart.common.result.R;
import com.lingmart.shop.dto.ApplyShopDTO;
import com.lingmart.shop.dto.ReviewApplicationDTO;
import com.lingmart.shop.entity.AuditLog;
import com.lingmart.shop.entity.Shop;
import com.lingmart.shop.entity.ShopApplication;
import com.lingmart.shop.feign.AuthFeignClient;
import com.lingmart.shop.feign.ProductFeignClient;
import com.lingmart.shop.mapper.AuditLogMapper;
import com.lingmart.shop.mapper.ShopApplicationMapper;
import com.lingmart.shop.mapper.ShopMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 店铺服务单元测试：开店申请三态规则 + 审核通过/拒绝（Mockito，不依赖数据库）。
 */
@ExtendWith(MockitoExtension.class)
class ShopServiceImplTest {

    @Mock private ShopMapper shopMapper;
    @Mock private ShopApplicationMapper applicationMapper;
    @Mock private AuditLogMapper auditLogMapper;
    @Mock private AuthFeignClient authFeignClient;
    @Mock private ProductFeignClient productFeignClient;

    private ShopServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new ShopServiceImpl(shopMapper, applicationMapper, auditLogMapper,
                authFeignClient, productFeignClient, new ObjectMapper());
    }

    private ApplyShopDTO validDto() {
        ApplyShopDTO dto = new ApplyShopDTO();
        dto.setShopName("测试店铺");
        dto.setDescription("描述");
        dto.setProducts(List.of(new com.lingmart.common.dto.SpuDTO()));
        return dto;
    }

    /** 首次申请：创建店铺 + 入驻申请 + 调用商品服务建初始商品 */
    @Test
    void apply_首次申请创建店铺与申请() {
        when(shopMapper.selectOne(any())).thenReturn(null);
        doAnswer(inv -> { ((Shop) inv.getArgument(0)).setId(100L); return 1; }).when(shopMapper).insert(any(Shop.class));
        doAnswer(inv -> { ((ShopApplication) inv.getArgument(0)).setId(200L); return 1; }).when(applicationMapper).insert(any(ShopApplication.class));
        when(productFeignClient.createForApplication(eq(100L), eq(200L), any())).thenReturn(R.ok(List.of(1L)));

        service.apply(1L, validDto());

        verify(shopMapper).insert(any(Shop.class));
        verify(applicationMapper).insert(any(ShopApplication.class));
        verify(productFeignClient).createForApplication(eq(100L), eq(200L), any());
    }

    /** 营业中的店铺：禁止再次申请 */
    @Test
    void apply_营业中禁止重复申请() {
        Shop shop = new Shop();
        shop.setStatus(ShopServiceImpl.SHOP_ENABLED);
        shop.setReviewStatus(ShopServiceImpl.REVIEW_APPROVED);
        when(shopMapper.selectOne(any())).thenReturn(shop);

        assertThrows(BizException.class, () -> service.apply(1L, validDto()));
        verify(applicationMapper, never()).insert(any(ShopApplication.class));
    }

    /** 审核中：审核完成前拒绝再次申请 */
    @Test
    void apply_待审核中禁止再次申请() {
        Shop shop = new Shop();
        shop.setStatus(ShopServiceImpl.SHOP_PENDING);
        shop.setReviewStatus(ShopServiceImpl.REVIEW_PENDING);
        when(shopMapper.selectOne(any())).thenReturn(shop);

        assertThrows(BizException.class, () -> service.apply(1L, validDto()));
        verify(applicationMapper, never()).insert(any(ShopApplication.class));
    }

    /** 已禁用店铺：禁止重新申请 */
    @Test
    void apply_已禁用店铺禁止重新申请() {
        Shop shop = new Shop();
        shop.setStatus(ShopServiceImpl.SHOP_DISABLED);
        shop.setReviewStatus(ShopServiceImpl.REVIEW_REJECTED);
        when(shopMapper.selectOne(any())).thenReturn(shop);

        assertThrows(BizException.class, () -> service.apply(1L, validDto()));
    }

    /** 被拒绝后：复用原 shop 行重新申请（重置为待审核 + 新建申请） */
    @Test
    void apply_被拒绝后复用原店铺行重新申请() {
        Shop shop = new Shop();
        shop.setId(10L);
        shop.setStatus(ShopServiceImpl.SHOP_PENDING);
        shop.setReviewStatus(ShopServiceImpl.REVIEW_REJECTED);
        when(shopMapper.selectOne(any())).thenReturn(shop);
        when(productFeignClient.createForApplication(eq(10L), any(), any())).thenReturn(R.ok(List.of()));

        service.apply(1L, validDto());

        ArgumentCaptor<Shop> captor = ArgumentCaptor.forClass(Shop.class);
        verify(shopMapper).updateById(captor.capture());
        assertEquals(ShopServiceImpl.SHOP_PENDING, captor.getValue().getStatus());
        assertEquals(ShopServiceImpl.REVIEW_PENDING, captor.getValue().getReviewStatus());
        verify(shopMapper, never()).insert(any(Shop.class));   // 复用而非新增
        verify(applicationMapper).insert(any(ShopApplication.class));
    }

    /** 申请必须携带商品 */
    @Test
    void apply_必须携带商品清单() {
        ApplyShopDTO dto = new ApplyShopDTO();
        dto.setShopName("无商品");
        assertThrows(BizException.class, () -> service.apply(1L, dto));
    }

    /** 审核通过：申请通过 + 店铺启用 + 商品按申请通过（传店铺名，避免回调）+ 提升角色 + 审计 */
    @Test
    void review_审核通过链路() {
        ShopApplication app = new ShopApplication();
        app.setId(1L);
        app.setUserId(2L);
        app.setShopName("测试店铺");
        app.setStatus(ShopServiceImpl.APPLY_PENDING);
        when(applicationMapper.selectById(1L)).thenReturn(app);
        when(productFeignClient.approveByApplication(eq(1L), eq("测试店铺"))).thenReturn(R.ok());
        when(authFeignClient.updateRole(any())).thenReturn(R.ok());

        ReviewApplicationDTO dto = new ReviewApplicationDTO();
        dto.setApprove(true);
        service.review(AuthConstants.ROLE_ADMIN, 99L, 1L, dto);

        // 传入店铺名（商品服务无需在全局事务内回调本服务）
        verify(productFeignClient).approveByApplication(eq(1L), eq("测试店铺"));
        // 角色提升为卖家
        ArgumentCaptor<UserRoleUpdateRequest> reqCaptor = ArgumentCaptor.forClass(UserRoleUpdateRequest.class);
        verify(authFeignClient).updateRole(reqCaptor.capture());
        assertEquals(2L, reqCaptor.getValue().getUserId());
        assertEquals(AuthConstants.ROLE_SELLER, reqCaptor.getValue().getRole());
        // 写入审计
        verify(auditLogMapper).insert(any(AuditLog.class));
    }

    /** 审核拒绝：申请拒绝 + 商品拒绝 + 审计（不提升角色） */
    @Test
    void review_审核拒绝链路() {
        ShopApplication app = new ShopApplication();
        app.setId(1L);
        app.setUserId(2L);
        app.setShopName("测试店铺");
        app.setStatus(ShopServiceImpl.APPLY_PENDING);
        when(applicationMapper.selectById(1L)).thenReturn(app);
        when(productFeignClient.rejectByApplication(1L)).thenReturn(R.ok());

        ReviewApplicationDTO dto = new ReviewApplicationDTO();
        dto.setApprove(false);
        dto.setRejectReason("资质不符");
        service.review(AuthConstants.ROLE_ADMIN, 99L, 1L, dto);

        verify(productFeignClient).rejectByApplication(1L);
        verify(authFeignClient, never()).updateRole(any());
        verify(auditLogMapper).insert(any(AuditLog.class));
    }

    /** 非管理员审核 → 403 */
    @Test
    void review_非管理员无权限() {
        ReviewApplicationDTO dto = new ReviewApplicationDTO();
        dto.setApprove(true);
        assertThrows(BizException.class, () -> service.review(AuthConstants.ROLE_USER, 99L, 1L, dto));
        verify(applicationMapper, never()).selectById(any());
    }
}
