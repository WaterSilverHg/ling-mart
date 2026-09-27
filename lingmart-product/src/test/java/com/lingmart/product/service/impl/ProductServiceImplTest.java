package com.lingmart.product.service.impl;

import com.alibaba.csp.sentinel.slots.block.BlockException;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRule;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lingmart.common.dto.SkuDTO;
import com.lingmart.common.dto.SpuDTO;
import com.lingmart.common.exception.BizException;
import com.lingmart.product.entity.AuditLog;
import com.lingmart.product.entity.Sku;
import com.lingmart.product.entity.Spu;
import com.lingmart.product.es.EsProductService;
import com.lingmart.product.feign.ShopFeignClient;
import com.lingmart.product.feign.WareFeignClient;
import com.lingmart.product.mapper.AuditLogMapper;
import com.lingmart.product.mapper.BrandMapper;
import com.lingmart.product.mapper.CategoryMapper;
import com.lingmart.product.mapper.SaleRollbackLogMapper;
import com.lingmart.product.mapper.SkuImageMapper;
import com.lingmart.product.mapper.SkuMapper;
import com.lingmart.product.mapper.SpuMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 商品服务单元测试：开店建商品、按申请审核（去回调）、单商品审核、Sentinel 降级（Mockito）。
 */
@ExtendWith(MockitoExtension.class)
class ProductServiceImplTest {

    // 以下 @Mock 的声明顺序必须与 ProductServiceImpl 的字段声明顺序一致：
    // 被测类用 Lombok @RequiredArgsConstructor 生成构造器，参数顺序 = 字段声明顺序。
    // 新增依赖字段时，此处必须同步补 Mock 与构造参数（漏了会在编译期报
    // "The constructor ProductServiceImpl(...) is undefined"，而不是运行期报错）。
    @Mock private SpuMapper spuMapper;
    @Mock private SkuMapper skuMapper;
    @Mock private SkuImageMapper skuImageMapper;
    @Mock private CategoryMapper categoryMapper;
    @Mock private BrandMapper brandMapper;
    @Mock private AuditLogMapper auditLogMapper;
    @Mock private SaleRollbackLogMapper rollbackLogMapper;
    @Mock private ShopFeignClient shopFeignClient;
    @Mock private WareFeignClient wareFeignClient;
    @Mock private EsProductService esProductService;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private StringRedisTemplate stringRedisTemplate;

    private ProductServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new ProductServiceImpl(spuMapper, skuMapper, skuImageMapper, categoryMapper, brandMapper,
                auditLogMapper, rollbackLogMapper, shopFeignClient, wareFeignClient, esProductService,
                new ObjectMapper(), eventPublisher, stringRedisTemplate);
    }

    private SpuDTO productDto() {
        SpuDTO dto = new SpuDTO();
        dto.setTitle("测试商品");
        dto.setMainImage("/uploads/x.jpg");
        SkuDTO sku = new SkuDTO();
        sku.setTitle("黑色");
        sku.setPrice(new java.math.BigDecimal("99.00"));
        sku.setStock(10);
        sku.setImages(List.of("/uploads/b.jpg"));
        dto.setSkus(List.of(sku));
        return dto;
    }

    /** 开店申请批量建商品：SPU + SKU 入库 + 同步库存，均为待审核 */
    @Test
    void createProductsForApplication_建待审核商品并同步库存() {
        doAnswer(inv -> { ((Spu) inv.getArgument(0)).setId(1L); return 1; }).when(spuMapper).insert(any(Spu.class));
        doAnswer(inv -> { ((Sku) inv.getArgument(0)).setId(11L); return 1; }).when(skuMapper).insert(any(Sku.class));

        List<Long> ids = service.createProductsForApplication(5L, 6L, List.of(productDto()));

        assertEquals(List.of(1L), ids);
        // 同步库存到仓储服务（每次建 SPU 调一次）
        verify(wareFeignClient).syncStock(anyList());
    }

    /** 按申请审核通过：直接上架首次通过的商品，且不再反向回调 shop 服务 */
    @Test
    void approveByApplication_通过并上架且不回调shop() {
        Spu spu = new Spu();
        spu.setId(1L);
        spu.setShopId(5L);
        spu.setStatus(ProductServiceImpl.SPU_OFF);
        spu.setReviewStatus(ProductServiceImpl.REVIEW_PENDING);
        when(spuMapper.selectList(any())).thenReturn(List.of(spu));
        // updateById 后重新读取为在售状态 → 刷新 ES
        Spu onShelf = new Spu();
        onShelf.setId(1L);
        onShelf.setShopId(5L);
        onShelf.setTitle("测试商品");
        onShelf.setStatus(ProductServiceImpl.SPU_ON);
        onShelf.setReviewStatus(ProductServiceImpl.REVIEW_APPROVED);
        when(spuMapper.selectById(1L)).thenReturn(onShelf);

        service.approveByApplication(6L, "店铺A");

        // 审核通过 → 上架（status=1）落库
        verify(spuMapper).updateById(any(Spu.class));
        // 关键：不再反向 Feign 回调 shop（Seata 全局事务内回调事务根是雷区）
        verify(shopFeignClient, never()).getShopById(anyLong());
        // 在售 → 刷新 ES 索引
        verify(esProductService).index(any());
    }

    /** 单商品审核拒绝：置为已拒绝 + 写审计，不触达 shop */
    @Test
    void reviewProduct_拒绝并写审计() {
        Spu spu = new Spu();
        spu.setId(1L);
        spu.setShopId(5L);
        when(spuMapper.selectById(1L)).thenReturn(spu);

        service.reviewProduct(9L, 1L, false, "图片不符");

        verify(spuMapper).updateById(any(Spu.class));
        verify(auditLogMapper).insert(any(AuditLog.class));
    }

    /** Sentinel 降级：商品详情被限流时抛业务异常（可读提示，而非 500） */
    @Test
    void getDetailBlock_限流降级抛业务异常() {
        BlockException be = mock(BlockException.class);
        when(be.getRule()).thenReturn(new FlowRule());
        BizException ex = assertThrows(BizException.class, () -> ProductServiceImpl.getDetailBlock(1L, be));
        assertEquals("请求过于频繁，请稍后再试", ex.getMessage());
    }

    /** 商品不存在时审核抛业务异常 */
    @Test
    void reviewProduct_商品不存在() {
        when(spuMapper.selectById(1L)).thenReturn(null);
        assertThrows(BizException.class, () -> service.reviewProduct(9L, 1L, true, null));
        verify(auditLogMapper, never()).insert(any(AuditLog.class));
    }
}
