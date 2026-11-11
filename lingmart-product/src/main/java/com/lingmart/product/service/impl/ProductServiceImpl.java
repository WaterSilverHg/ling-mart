package com.lingmart.product.service.impl;

import com.alibaba.csp.sentinel.annotation.SentinelResource;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import com.alibaba.csp.sentinel.slots.block.degrade.DegradeRule;
import com.alibaba.csp.sentinel.slots.block.flow.FlowRule;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lingmart.common.constant.AuthConstants;
import com.lingmart.common.dto.SaleItem;
import com.lingmart.common.dto.ShopInfo;
import com.lingmart.common.dto.ShopProductItem;
import com.lingmart.common.dto.SkuDTO;
import com.lingmart.common.dto.SpuDTO;
import com.lingmart.common.dto.StockSyncItem;
import com.lingmart.common.dto.WareStockInfo;
import com.lingmart.common.exception.BizException;
import com.lingmart.common.result.PageResult;
import com.lingmart.common.result.R;
import com.lingmart.common.result.ResultCode;
import com.lingmart.common.util.AuthUtils;
import com.lingmart.product.entity.AuditLog;
import com.lingmart.product.entity.Brand;
import com.lingmart.product.entity.Category;
import com.lingmart.product.entity.SaleRollbackLog;
import com.lingmart.product.entity.Sku;
import com.lingmart.product.entity.SkuImage;
import com.lingmart.product.entity.Spu;
import com.lingmart.product.es.EsProduct;
import com.lingmart.product.es.EsProductService;
import com.lingmart.product.event.SaleUpdateEvent;
import com.lingmart.product.feign.ShopFeignClient;
import com.lingmart.product.feign.WareFeignClient;
import com.lingmart.product.mapper.AuditLogMapper;
import com.lingmart.product.mapper.BrandMapper;
import com.lingmart.product.mapper.CategoryMapper;
import com.lingmart.product.mapper.SaleRollbackLogMapper;
import com.lingmart.product.mapper.SkuImageMapper;
import com.lingmart.product.mapper.SkuMapper;
import com.lingmart.product.mapper.SpuMapper;
import com.lingmart.product.service.ProductService;
import com.lingmart.product.vo.ProductDetailVO;
import com.lingmart.product.vo.ProductReviewVO;
import com.lingmart.product.vo.ProductVO;
import com.lingmart.product.vo.SkuDetailVO;
import com.lingmart.product.vo.SkuStockVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 商品服务实现（审核制模型）。
 *
 * <p>数据隔离：卖家操作先经 Feign 取当前用户店铺，再校验商品归属。审核状态机：
 * 0待审核 → 1已通过 → 2已拒绝 / 3待重审。内容编辑走"宽松重审"：新内容写入草稿，
 * 旧内容照常销售，审核通过才生效；库存变更不重审。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProductServiceImpl implements ProductService {

    /** 销售状态：下架 / 上架 */
    public static final int SPU_OFF = 0;
    public static final int SPU_ON = 1;
    /** 审核状态：待审核 / 已通过 / 已拒绝 / 待重审 */
    public static final int REVIEW_PENDING = 0;
    public static final int REVIEW_APPROVED = 1;
    public static final int REVIEW_REJECTED = 2;
    public static final int REVIEW_REVIEWING = 3;
    /** 店铺营业状态 */
    public static final int SHOP_ENABLED = 1;

    private final SpuMapper spuMapper;
    private final SkuMapper skuMapper;
    private final SkuImageMapper skuImageMapper;
    private final CategoryMapper categoryMapper;
    private final BrandMapper brandMapper;
    private final AuditLogMapper auditLogMapper;
    private final SaleRollbackLogMapper rollbackLogMapper;
    private final ShopFeignClient shopFeignClient;
    private final WareFeignClient wareFeignClient;
    private final EsProductService esProductService;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher eventPublisher;
    private final StringRedisTemplate stringRedisTemplate;

    /** 店铺名缓存 TTL（销量刷新等高频场景避免对 shop 服务的重复 Feign 网络 IO） */
    private static final long SHOP_NAME_TTL_MS = 5 * 60_000L;

    /**
     * 店铺名缓存 Key 前缀。放 Redis 而非本地 Map，是为了多实例一致：改名审核由某个实例处理并重建 ES 后，
     * 其它实例必须立刻看到新名，否则它们在 TTL 内的一次销量刷新会用旧店名把整份文档写回去。
     */
    private static final String SHOP_NAME_CACHE_PREFIX = "lingmart:shop:name:";

    /** 店铺改名后重建 ES 文档的单批大小 */
    private static final int SHOP_REINDEX_PAGE = 200;

    /** 店铺改名后重建 ES 文档的上限（防止超大店铺一次全量重索引） */
    private static final int SHOP_REINDEX_MAX = 1000;

    /** 反向对账单次扫描的 ES 文档上限（防止一次拉全索引把内存打满） */
    @Value("${lingmart.es.ghost-scan-limit:1000}")
    private int ghostScanLimit;

    /**
     * 卖家发布商品：SPU + SKU + 库存同步。
     *
     * <p><b>事务取舍</b>：本方法（以及 {@link #updateStock}）把 {@code wareFeignClient.syncStock}
     * 刻意<b>留在事务内</b>——审计建议把它挪到提交后，但那会留下「SPU/SKU 已提交、ware 无对应库存行」
     * 或「有 SPU 没 SKU」的半成品状态，卖家不重新保存就永久无法下单。建品/改库存是低频的卖家操作，
     * 十几行写入换一次内网 Feign 调用，宁可短事务持锁也不要脏数据。</p>
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long createProduct(Long userId, SpuDTO dto) {
        ShopInfo shop = requireEnabledShop(userId);
        Spu spu = buildSpu(shop.getId(), dto);
        spu.setStatus(SPU_OFF);
        spu.setReviewStatus(REVIEW_PENDING); // 新商品待审核，未上架
        spuMapper.insert(spu);

        List<StockSyncItem> stockItems = insertSkus(spu.getId(), shop.getId(), dto.getSkus());
        recalcMinPrice(spu.getId());
        wareFeignClient.syncStock(stockItems);
        log.info("卖家发布商品（待审核）：spuId={}, shopId={}, userId={}", spu.getId(), shop.getId(), userId);
        return spu.getId();
    }

    @Override
    public void updateProduct(Long userId, Long spuId, SpuDTO dto) {
        ShopInfo shop = requireEnabledShop(userId);
        Spu spu = requireOwnedSpu(shop, spuId);
        if (!Objects.equals(spu.getReviewStatus(), REVIEW_APPROVED)
                && !Objects.equals(spu.getReviewStatus(), REVIEW_REJECTED)
                && !Objects.equals(spu.getReviewStatus(), REVIEW_REVIEWING)) {
            throw new BizException("商品尚未通过首次审核，不能编辑");
        }

        // 宽松重审：新内容写入草稿，旧内容照常销售
        Spu update = new Spu();
        update.setId(spuId);
        update.setReviewStatus(REVIEW_REVIEWING);
        update.setDraftContent(toJson(dto));
        spuMapper.updateById(update);
        log.info("商品内容变更进入待重审：spuId={}, userId={}", spuId, userId);
    }

    /** 卖家改库存：事务取舍同 {@link #createProduct}（Feign 留在事务内，避免 SKU 已改而 ware 未同步）。 */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateStock(Long userId, Long spuId, List<SkuDTO> skus) {
        ShopInfo shop = requireEnabledShop(userId);
        requireOwnedSpu(shop, spuId);
        if (skus == null) {
            return;
        }
        for (SkuDTO dto : skus) {
            if (dto.getId() == null) {
                throw new BizException("SKU ID 不能为空");
            }
            Sku sku = skuMapper.selectById(dto.getId());
            if (sku == null || !Objects.equals(sku.getSpuId(), spuId)
                    || !Objects.equals(sku.getShopId(), shop.getId())) {
                throw new BizException(ResultCode.FORBIDDEN.getCode(), "无权操作该 SKU");
            }
            Sku update = new Sku();
            update.setId(sku.getId());
            // 库存语义：NULL = 不限量；0..N = 限量可售（0=售罄）。此处 NULL 不再代表「保持原值」，
            // 改库存接口约定调用方必须显式传数量或传 NULL 以置为不限量。
            update.setStock(dto.getStock());
            skuMapper.updateById(update);
        }
        // 同步最新库存到仓储服务（不触发重审）
        wareFeignClient.syncStock(collectStockSyncItems(spuId, shop.getId()));
        log.info("库存已更新（不重审）：spuId={}", spuId);
    }

    @Override
    public void onShelf(Long userId, Long spuId) {
        ShopInfo shop = requireEnabledShop(userId);
        Spu spu = requireOwnedSpu(shop, spuId);
        if (!Objects.equals(spu.getReviewStatus(), REVIEW_APPROVED)) {
            throw new BizException("商品未通过审核，无法上架");
        }
        if (Objects.equals(spu.getStatus(), SPU_ON)) {
            throw new BizException("商品已上架");
        }
        Spu update = new Spu();
        update.setId(spuId);
        update.setStatus(SPU_ON);
        spuMapper.updateById(update);
        spu.setStatus(SPU_ON);
        esProductService.index(buildEsDoc(spu, shop.getShopName()));
        log.info("商品上架：spuId={}, shopId={}", spuId, shop.getId());
    }

    @Override
    public void offShelf(Long userId, Long spuId) {
        ShopInfo shop = requireEnabledShop(userId);
        Spu spu = requireOwnedSpu(shop, spuId);
        if (Objects.equals(spu.getStatus(), SPU_OFF)) {
            throw new BizException("商品已下架");
        }
        Spu update = new Spu();
        update.setId(spuId);
        update.setStatus(SPU_OFF);
        spuMapper.updateById(update);
        esProductService.delete(spuId.toString());
        log.info("商品下架：spuId={}, shopId={}", spuId, shop.getId());
    }

    @Override
    @SentinelResource(value = "getProductDetail", blockHandler = "getDetailBlock")
    // sync=true：同一热键并发回源时缓存层加锁，只让一个线程查库，防热点击穿
    @Cacheable(cacheNames = "productDetail", key = "#spuId", sync = true)
    public ProductDetailVO getDetail(Long spuId) {
        Spu spu = spuMapper.selectById(spuId);
        if (spu == null) {
            // 穿透保护：返回 null 并缓存空结果（productDetail 缓存允许 null，短 TTL），
            // 避免不存在的商品 ID 每次都打库；由 Controller 将 null 转「商品不存在」业务异常
            return null;
        }
        return toDetailVO(spu);
    }

    @Override
    public ProductDetailVO getDetailWithStock(Long spuId) {
        // 静态内容命中 productDetail 缓存，返回的是共享实例——禁止原地改写；
        // 实时数量单独回源仓储，克隆一份再填充，避免污染缓存。
        ProductDetailVO base = getDetail(spuId);
        if (base == null) {
            return null;
        }
        ProductDetailVO vo = cloneDetail(base);
        attachLiveStock(vo.getSkus());
        return vo;
    }

    @Override
    public ProductDetailVO detailForView(Long spuId, Long userId) {
        Spu spu = spuMapper.selectById(spuId);
        if (spu == null) {
            return null;
        }
        // 公开条件：上架且审核已通过。公开商品所有人可见（走原详情组装 + 实时库存/缓存）。
        boolean publicOnShelf = Objects.equals(spu.getStatus(), SPU_ON)
                && Objects.equals(spu.getReviewStatus(), REVIEW_APPROVED);
        if (publicOnShelf) {
            return getDetailWithStock(spuId);
        }
        // 未上架/待审核/已拒绝/下架 = 卖家私有预览：仅属主可见；非属主与“商品不存在”同语义，防枚举探测
        if (!isShopOwner(userId, spu.getShopId())) {
            return null;
        }
        ProductDetailVO vo = toDetailVO(spu);
        attachLiveStock(vo.getSkus());
        return vo;
    }

    /**
     * 浅克隆商品详情（SKU 逐个克隆），避免实时库存回源改写缓存的共享实例。
     */
    private ProductDetailVO cloneDetail(ProductDetailVO src) {
        ProductDetailVO vo = new ProductDetailVO();
        vo.setSpuId(src.getSpuId());
        vo.setShopId(src.getShopId());
        vo.setShopName(src.getShopName());
        vo.setTitle(src.getTitle());
        vo.setSubtitle(src.getSubtitle());
        vo.setCategoryId(src.getCategoryId());
        vo.setCategoryName(src.getCategoryName());
        vo.setBrandId(src.getBrandId());
        vo.setBrandName(src.getBrandName());
        vo.setMainImage(src.getMainImage());
        vo.setDescription(src.getDescription());
        vo.setStatus(src.getStatus());
        vo.setReviewStatus(src.getReviewStatus());
        vo.setSaleCount(src.getSaleCount());
        vo.setPrice(src.getPrice());
        vo.setSkus(src.getSkus() == null ? null
                : src.getSkus().stream().map(this::cloneSkuDetail).toList());
        return vo;
    }

    private SkuDetailVO cloneSkuDetail(SkuDetailVO s) {
        SkuDetailVO c = new SkuDetailVO();
        c.setId(s.getId());
        c.setTitle(s.getTitle());
        c.setPrice(s.getPrice());
        c.setStock(s.getStock());
        c.setUnlimited(s.getUnlimited());
        c.setSaleCount(s.getSaleCount());
        c.setStatus(s.getStatus());
        c.setImages(s.getImages() == null ? null : new ArrayList<>(s.getImages()));
        return c;
    }

    /**
     * Sentinel 限流降级：详情被限流时抛业务异常（由全局异常处理器转 R JSON 提示）。
     * 签名须与 {@link #getDetail} 一致并追加 BlockException。
     */
    public static ProductDetailVO getDetailBlock(Long spuId, BlockException ex) {
        double qpsLimit = 0.0;
        if (ex.getRule() instanceof FlowRule) {
            qpsLimit = ((FlowRule) ex.getRule()).getCount();
        } else if (ex.getRule() instanceof DegradeRule) {
            qpsLimit = ((DegradeRule) ex.getRule()).getCount();
        }
        log.warn("商品详情被限流降级：spuId={}, qpsLimit={}", spuId, qpsLimit);
        throw new BizException("请求过于频繁，请稍后再试");
    }

    @Override
    public PageResult<SkuDetailVO> pageSkus(Long spuId, long page, long size) {
        if (spuMapper.selectById(spuId) == null) {
            throw new BizException("商品不存在");
        }
        Page<Sku> p = skuMapper.selectPage(new Page<>(page, size),
                Wrappers.<Sku>lambdaQuery().eq(Sku::getSpuId, spuId).orderByAsc(Sku::getId));
        // 本页 SKU 图片一次批量取回（旧写法每行一次查询）
        List<SkuDetailVO> records = toSkuDetailVOList(p.getRecords());
        // 分页接口每次新建对象（未走缓存），直接回源实时数量后写入
        attachLiveStock(records);
        return PageResult.of(records, p.getTotal(), page, size);
    }

    @Override
    public PageResult<SkuDetailVO> pageSkusForView(Long spuId, Long userId, long page, long size) {
        Spu spu = spuMapper.selectById(spuId);
        boolean publicOnShelf = spu != null && Objects.equals(spu.getStatus(), SPU_ON)
                && Objects.equals(spu.getReviewStatus(), REVIEW_APPROVED);
        boolean owner = spu != null && isShopOwner(userId, spu.getShopId());
        if (spu == null || (!publicOnShelf && !owner)) {
            // 不存在或非属主访问非公开商品：统一“商品不存在”，避免枚举探测
            throw new BizException("商品不存在");
        }
        return pageSkus(spuId, page, size);
    }

    @Override
    public PageResult<ProductVO> myProducts(Long userId, long page, long size, Integer status, Integer reviewStatus) {
        if (userId == null) {
            throw new BizException(ResultCode.UNAUTHORIZED.getCode(), "未登录或登录已过期");
        }
        R<ShopInfo> result = shopFeignClient.getShopByUserId(userId);
        ShopInfo shop = result == null ? null : result.getData();
        if (shop == null) {
            return PageResult.of(List.of(), 0, page, size);
        }
        // 筛选条件可选：不传即不筛选（保持与旧调用方完全一致的行为）
        Page<Spu> p = spuMapper.selectPage(new Page<>(page, size),
                Wrappers.<Spu>lambdaQuery()
                        .eq(Spu::getShopId, shop.getId())
                        .eq(status != null, Spu::getStatus, status)
                        .eq(reviewStatus != null, Spu::getReviewStatus, reviewStatus)
                        .orderByDesc(Spu::getCreateTime));
        List<ProductVO> list = p.getRecords().stream().map(this::toProductVO).toList();
        // 列表直接带回各 SKU 实时数量（不限量/剩余件数），供「我的商品」数量列展示
        fillMyProductStock(list);
        return PageResult.of(list, p.getTotal(), page, size);
    }

    @Override
    public PageResult<ProductVO> listOnShelf(long page, long size) {
        // 仅返回审核通过且在售的商品
        Page<Spu> p = spuMapper.selectPage(new Page<>(page, size),
                Wrappers.<Spu>lambdaQuery()
                        .eq(Spu::getStatus, SPU_ON)
                        .eq(Spu::getReviewStatus, REVIEW_APPROVED)
                        .orderByDesc(Spu::getCreateTime));
        return PageResult.of(p.getRecords().stream().map(this::toProductVO).toList(),
                p.getTotal(), page, size);
    }

    @Override
    public List<ShopProductItem> listOnShelfByShop(Long shopId) {
        // 店铺主页：仅该店铺审核通过且在售的商品，按上架时间倒序
        return spuMapper.selectList(Wrappers.<Spu>lambdaQuery()
                        .eq(Spu::getShopId, shopId)
                        .eq(Spu::getStatus, SPU_ON)
                        .eq(Spu::getReviewStatus, REVIEW_APPROVED)
                        .orderByDesc(Spu::getCreateTime)
                        .last("LIMIT 100"))
                .stream()
                .map(spu -> {
                    ShopProductItem item = new ShopProductItem();
                    item.setSpuId(spu.getId());
                    item.setTitle(spu.getTitle());
                    item.setMainImage(spu.getMainImage());
                    item.setPrice(priceOf(spu));
                    item.setSaleCount(spu.getSaleCount());
                    return item;
                })
                .toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public List<Long> createProductsForApplication(Long shopId, Long applicationId, List<SpuDTO> products) {
        List<Long> ids = new ArrayList<>();
        for (SpuDTO dto : products) {
            Spu spu = buildSpu(shopId, dto);
            spu.setStatus(SPU_OFF);
            spu.setReviewStatus(REVIEW_PENDING);
            spu.setApplicationId(applicationId);
            spuMapper.insert(spu);
            wareFeignClient.syncStock(insertSkus(spu.getId(), shopId, dto.getSkus()));
            recalcMinPrice(spu.getId());
            ids.add(spu.getId());
        }
        log.info("入驻申请创建初始商品：applicationId={}, count={}", applicationId, products.size());
        return ids;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void approveByApplication(Long applicationId, String shopName) {
        List<Spu> list = spuMapper.selectList(Wrappers.<Spu>lambdaQuery().eq(Spu::getApplicationId, applicationId));
        for (Spu spu : list) {
            approveInternal(spu, shopName);
        }
        log.info("入驻申请审核通过，商品全部上架：applicationId={}, count={}", applicationId, list.size());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void rejectByApplication(Long applicationId) {
        List<Spu> list = spuMapper.selectList(Wrappers.<Spu>lambdaQuery().eq(Spu::getApplicationId, applicationId));
        for (Spu spu : list) {
            Spu update = new Spu();
            update.setId(spu.getId());
            update.setReviewStatus(REVIEW_REJECTED);
            update.setDraftContent(null);
            spuMapper.updateById(update);
        }
        log.info("入驻申请审核拒绝：applicationId={}, count={}", applicationId, list.size());
    }

    @Override
    public PageResult<ProductReviewVO> reviewList(Integer reviewStatus, long page, long size) {
        Page<Spu> p = spuMapper.selectPage(new Page<>(page, size),
                Wrappers.<Spu>lambdaQuery()
                        .eq(reviewStatus != null, Spu::getReviewStatus, reviewStatus)
                        .eq(reviewStatus == null, Spu::getReviewStatus, REVIEW_PENDING)
                        .orderByAsc(Spu::getCreateTime));
        // 当页店铺一次批量取回（旧写法每行一次 Feign）
        return PageResult.of(toReviewVOList(p.getRecords()),
                p.getTotal(), page, size);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void reviewProduct(Long operatorId, Long spuId, boolean approve, String rejectReason) {
        Spu spu = spuMapper.selectById(spuId);
        if (spu == null) {
            throw new BizException("商品不存在");
        }
        if (approve) {
            // 单商品审核不在 shop 的全局事务内，此处 Feign 回调 shop 取店名安全（且已空安全兜底）
            approveInternal(spu, shopNameOf(spu.getShopId()));
        } else {
            // 拒绝：丢弃草稿；已通过的旧内容继续销售（宽松），新商品保持未上架
            Spu update = new Spu();
            update.setId(spuId);
            update.setReviewStatus(REVIEW_REJECTED);
            update.setDraftContent(null);
            spuMapper.updateById(update);
            log.info("商品审核拒绝：spuId={}, reason={}", spuId, rejectReason);
        }
        recordAudit(operatorId, spuId, approve ? 1 : 0, approve ? null : rejectReason);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void reviewProducts(Long operatorId, List<Long> spuIds, boolean approve, String rejectReason) {
        if (spuIds == null || spuIds.isEmpty()) {
            return;
        }
        for (Long spuId : spuIds) {
            reviewProduct(operatorId, spuId, approve, rejectReason);
        }
        log.info("批量审核商品：count={}, approve={}", spuIds.size(), approve);
    }

    @Override
    public PageResult<AuditLog> auditList(String role, long page, long size) {
        AuthUtils.requireRole(role, AuthConstants.ROLE_ADMIN);
        Page<AuditLog> p = auditLogMapper.selectPage(new Page<>(page, size),
                Wrappers.<AuditLog>lambdaQuery().orderByDesc(AuditLog::getCreateTime));
        return PageResult.of(p.getRecords(), p.getTotal(), page, size);
    }

    /** 写入审核审计日志（best-effort：失败不影响审核结果） */
    private void recordAudit(Long operatorId, Long spuId, int result, String reason) {
        try {
            AuditLog audit = new AuditLog();
            audit.setOperatorId(operatorId);
            audit.setAction("PRODUCT_REVIEW");
            audit.setTargetId(spuId);
            audit.setResult(result);
            audit.setReason(reason);
            auditLogMapper.insert(audit);
        } catch (Exception e) {
            log.warn("审计日志写入失败（不影响审核）：spuId={}, err={}", spuId, e.getMessage());
        }
    }

    @Override
    public List<ProductReviewVO> reviewListByApplication(Long applicationId) {
        List<Spu> spus = spuMapper.selectList(Wrappers.<Spu>lambdaQuery()
                .eq(Spu::getApplicationId, applicationId)
                .orderByAsc(Spu::getCreateTime));
        return toReviewVOList(spus);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateSale(List<SaleItem> items) {
        if (items == null) {
            return;
        }
        for (SaleItem item : items) {
            // 销量增量经 Math.max 钳制为非负后，以 {0} 占位符走参数绑定（避免字符串拼接 SQL）
            int num = Math.max(item.getNum() == null ? 0 : item.getNum(), 0);
            if (item.getSpuId() != null) {
                spuMapper.update(null, Wrappers.<Spu>lambdaUpdate()
                        .setSql("sale_count = sale_count + {0}", num)
                        .eq(Spu::getId, item.getSpuId()));
            }
            if (item.getSkuId() != null) {
                skuMapper.update(null, Wrappers.<Sku>lambdaUpdate()
                        .setSql("sale_count = sale_count + {0}", num)
                        .eq(Sku::getId, item.getSkuId()));
            }
        }
        // 销量刷新 ES 移出支付事务：发布事件，由监听器在本地事务提交后异步执行（AFTER_COMMIT + @Async），
        // 避免 ES/店名 Feign 网络 IO 拖住支付全局事务与 SPU 行锁；ES 最终一致即可（失败由 es_write_retry 兜底）
        if (items.stream().anyMatch(i -> i.getSpuId() != null)) {
            eventPublisher.publishEvent(new SaleUpdateEvent(items));
        }
        log.info("销量已更新并已提交异步 ES 刷新：items={}", items.size());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void rollbackSale(String orderSn, List<SaleItem> items) {
        if (items == null || items.isEmpty()) {
            return;
        }
        if (orderSn != null && !orderSn.isBlank()) {
            try {
                // 幂等键：同一订单只回退一次。记录与扣减同事务 —— 扣减失败会一并回滚，仍可安全重试
                SaleRollbackLog record = new SaleRollbackLog();
                record.setOrderSn(orderSn);
                rollbackLogMapper.insert(record);
            } catch (DuplicateKeyException e) {
                log.info("销量回退幂等命中（该订单已回退过），跳过：orderSn={}", orderSn);
                return;
            }
        }
        for (SaleItem item : items) {
            int num = Math.abs(item.getNum() == null ? 0 : item.getNum());
            if (num == 0) {
                continue;
            }
            if (item.getSpuId() != null) {
                // GREATEST 兜底：sale_count 为有符号 INT，历史数据可能小于回退量，绝不允许出现负销量。
                // rows==0 判「SPU 不存在」依赖 Connector/J 默认 useAffectedRows=false（返回匹配行数）；
                // 若将来有人开启 useAffectedRows=true，sale_count 已为 0 时会被误判为不存在。
                int rows = spuMapper.update(null, Wrappers.<Spu>lambdaUpdate()
                        .setSql("sale_count = GREATEST(sale_count - {0}, 0)", num)
                        .eq(Spu::getId, item.getSpuId()));
                if (rows == 0) {
                    throw new BizException("销量回退失败：商品不存在 spuId=" + item.getSpuId());
                }
            }
            if (item.getSkuId() != null) {
                // SKU 可能因卖家编辑商品被重建（旧 SKU 已删除）：不作为失败条件，仅记录
                int rows = skuMapper.update(null, Wrappers.<Sku>lambdaUpdate()
                        .setSql("sale_count = GREATEST(sale_count - {0}, 0)", num)
                        .eq(Sku::getId, item.getSkuId()));
                if (rows == 0) {
                    log.debug("销量回退跳过不存在的 SKU：skuId={}", item.getSkuId());
                }
            }
        }
        // 与 updateSale 一致：ES 销量刷新走事件（AFTER_COMMIT + @Async），失败由 es_write_retry/对账兜底
        if (items.stream().anyMatch(i -> i.getSpuId() != null)) {
            eventPublisher.publishEvent(new SaleUpdateEvent(items));
        }
        log.info("退款销量回退完成：items={}", items.size());
    }

    @Override
    public int refreshEsByShop(Long shopId, String shopName) {
        if (shopId == null) {
            return 0;
        }
        String resolved = shopName;
        if (resolved == null || resolved.isBlank()) {
            // 未传店名（管理端手动重建）：经 getShop 新鲜查询，不走 5 分钟本地缓存
            ShopInfo shop = getShop(shopId);
            resolved = shop == null ? null : shop.getShopName();
        }
        if (resolved == null || resolved.isBlank()) {
            return 0;
        }
        final String name = resolved;
        // 关键：先覆盖店名缓存。否则 5 分钟 TTL 内，支付链路的 refreshEsSales 仍会用旧店名
        // 重建整份文档，把刚改好的名字又写回旧值。
        writeShopNameCache(shopId, name);

        int total = 0;
        long lastId = 0L;
        while (total < SHOP_REINDEX_MAX) {
            List<Spu> batch = spuMapper.selectList(Wrappers.<Spu>lambdaQuery()
                    .eq(Spu::getShopId, shopId)
                    .eq(Spu::getStatus, SPU_ON)
                    .eq(Spu::getReviewStatus, REVIEW_APPROVED)
                    .gt(Spu::getId, lastId)
                    .orderByAsc(Spu::getId)
                    .last("LIMIT " + SHOP_REINDEX_PAGE));
            if (batch.isEmpty()) {
                break;
            }
            // 用入参店名构建文档（不再查缓存/Feign），确保本次重建用的就是新店名
            esProductService.indexAll(batch.stream().map(spu -> buildEsDoc(spu, name)).toList());
            total += batch.size();
            lastId = batch.get(batch.size() - 1).getId();
            if (batch.size() < SHOP_REINDEX_PAGE) {
                break;
            }
        }
        log.info("店铺改名后重建 ES 文档：shopId={}, shopName={}, 重建 {} 条", shopId, name, total);
        return total;
    }

    @Override
    public EsProduct loadEsDoc(Long spuId) {
        if (spuId == null) {
            return null;
        }
        Spu spu = spuMapper.selectById(spuId);
        if (spu == null || !Objects.equals(spu.getStatus(), SPU_ON)) {
            return null; // 已删除或已下架：调用方应删除对应 ES 文档
        }
        return buildEsDoc(spu, shopNameOf(spu.getShopId()));
    }

    @Override
    public void refreshEsSalesIndex(List<SaleItem> items) {
        try {
            refreshEsSales(items);
        } catch (Exception e) {
            // 兜底：异步链路异常不应静默丢——日志 + es_write_retry/对账任务后续兜底
            log.error("异步刷新 ES 销量失败（交由 es_write_retry/对账兜底）：err={}", e.getMessage());
        }
    }

    /** 刷新上架商品在 ES 中的销量字段（批量查询 SPU + 店名去重 + 批量写入，避免 N 次 DB/Feign/ES 往返） */
    private void refreshEsSales(List<SaleItem> items) {
        // 1. 去重 spuId，一次批量查询 SPU
        List<Long> spuIds = items.stream()
                .map(SaleItem::getSpuId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (spuIds.isEmpty()) {
            return;
        }
        Map<Long, Spu> spuMap = spuMapper.selectByIds(spuIds).stream()
                .filter(spu -> Objects.equals(spu.getStatus(), SPU_ON))
                .collect(Collectors.toMap(Spu::getId, spu -> spu));

        // 2. 同店铺店名只经 Feign 取一次（Feign 调用按店铺去重）
        Map<Long, String> shopNameCache = new HashMap<>();
        List<EsProduct> docs = new ArrayList<>();
        for (Spu spu : spuMap.values()) {
            String shopName = shopNameCache.computeIfAbsent(spu.getShopId(), this::shopNameOf);
            docs.add(buildEsDoc(spu, shopName));
        }

        // 3. 批量写入 ES（bulk），失败自动降级为逐条写入
        esProductService.indexAll(docs);
    }

    /**
     * ES ↔ MySQL 对账：周期扫描在售商品，缺失的 ES 文档重新索引（与 es_write_retry 补偿互补——
     * 补偿兜「写入那一刻的失败」，对账兜「事后任何原因导致的缺失/被清空」）。
     *
     * <p>分页遍历、店铺名按 Feign 去重取一次，检查失败（ES 不可用）跳过该轮避免误刷重试。</p>
     */
    @Scheduled(fixedDelayString = "${lingmart.es.reconcile-ms:300000}")
    public void reconcileEsIndex() {
        long size = 200;
        long current = 1;
        Page<Spu> page;
        int indexed = 0;
        do {
            page = spuMapper.selectPage(new Page<>(current, size),
                    Wrappers.<Spu>lambdaQuery()
                            .eq(Spu::getStatus, SPU_ON)
                            .eq(Spu::getReviewStatus, REVIEW_APPROVED)
                            .orderByAsc(Spu::getId));
            if (page.getRecords().isEmpty()) {
                break;
            }
            Map<Long, String> shopNameCache = new HashMap<>();
            List<EsProduct> missing = new ArrayList<>();
            for (Spu spu : page.getRecords()) {
                Boolean exists = esProductService.exists(spu.getId().toString());
                if (Boolean.TRUE.equals(exists) || exists == null) {
                    continue; // 存在或检查失败（ES 不可用）跳过
                }
                missing.add(buildEsDoc(spu, shopNameCache.computeIfAbsent(spu.getShopId(), this::shopNameOf)));
            }
            if (!missing.isEmpty()) {
                esProductService.indexAll(missing);
                indexed += missing.size();
            }
            current++;
        } while (current <= page.getPages());
        if (indexed > 0) {
            log.info("ES 对账完成：补索引 {} 个在售商品", indexed);
        }
        int removed = removeGhostDocs();
        if (removed > 0) {
            log.info("ES 反向对账完成：清理 {} 个幽灵文档", removed);
        }
    }

    /**
     * 反向对账：删除「ES 有、但 DB 中不该再出现」的幽灵文档。
     *
     * <p><b>为什么需要</b>：Seata AT 一阶段先把本地分支提交（含 ES 写入），全局回滚只撤销 DB、
     * 不会撤销 ES。于是搜索里会留下点进去「商品不存在」的文档，而正向对账只做「补缺失」，
     * 永远清不掉它们。</p>
     *
     * <p>判定口径与检索一致：SPU 必须存在、已上架（status=1）、审核通过（reviewStatus=1）。
     * 任一不满足即删除该 ES 文档。只扫描前 {@code lingmart.es.ghost-scan-limit} 条；
     * ES 不可用时 {@code findAllIds} 返回空列，本轮直接跳过。</p>
     */
    private int removeGhostDocs() {
        List<String> esIds = esProductService.findAllIds(ghostScanLimit);
        if (esIds.isEmpty()) {
            return 0;
        }
        List<Long> ids = new ArrayList<>(esIds.size());
        for (String esId : esIds) {
            try {
                ids.add(Long.valueOf(esId));
            } catch (NumberFormatException ignore) {
                // id 不是雪花数字 → 不是本服务写入的文档，不碰，避免误删
            }
        }
        if (ids.isEmpty()) {
            return 0;
        }
        Set<Long> alive = spuMapper.selectByIds(ids).stream()
                .filter(s -> Objects.equals(s.getStatus(), SPU_ON)
                        && Objects.equals(s.getReviewStatus(), REVIEW_APPROVED))
                .map(Spu::getId)
                .collect(Collectors.toSet());
        int removed = 0;
        for (Long id : ids) {
            if (!alive.contains(id)) {
                esProductService.delete(id.toString());
                removed++;
            }
        }
        return removed;
    }

    // ==================== 私有辅助方法 ====================

    /**
     * 审核通过：存在草稿则应用新内容（宽松重审），否则直接上架首次通过的商品。
     *
     * <p>店铺名由调用方传入（申请审核时由 shop 服务带过来；单商品审核时经 Feign 取店名），
     * 本方法内不再回调 shop 服务——避免在 Seata 全局事务中反向回调事务根（分支回根是雷区）。
     * {@code shopName} 可能为 null，仅影响 ES 索引里的店铺名展示，不影响审核结果。</p>
     *
     * @param spu      被审商品
     * @param shopName 店铺名称（可空）
     */
    private void approveInternal(Spu spu, String shopName) {
        boolean hasDraft = spu.getDraftContent() != null && !spu.getDraftContent().isBlank();

        Spu update = new Spu();
        update.setId(spu.getId());
        update.setReviewStatus(REVIEW_APPROVED);
        if (hasDraft) {
            // 应用草稿：覆盖生效内容 + 重建 SKU
            SpuDTO draft = fromJson(spu.getDraftContent());
            update.setTitle(draft.getTitle());
            update.setSubtitle(draft.getSubtitle());
            update.setCategoryId(draft.getCategoryId());
            update.setBrandId(draft.getBrandId());
            update.setMainImage(draft.getMainImage());
            update.setDescription(draft.getDescription());
            update.setDraftContent(null);
            spuMapper.updateById(update);
            rebuildSkus(spu.getId(), spu.getShopId(), draft.getSkus());
        } else {
            update.setStatus(SPU_ON); // 首次通过直接上架（minPrice 已在建商品时维护，无需重算）
            spuMapper.updateById(update);
        }

        // 刷新 ES（在售状态以最新内容入索引；ES 写入失败由 EsProductService 兜底只记日志）
        Spu latest = spuMapper.selectById(spu.getId());
        if (Objects.equals(latest.getStatus(), SPU_ON)) {
            esProductService.index(buildEsDoc(latest, shopName));
        }
        log.info("商品审核通过：spuId={}, hasDraft={}", spu.getId(), hasDraft);
    }

    /** 构建 SPU（不含状态字段） */
    private Spu buildSpu(Long shopId, SpuDTO dto) {
        Spu spu = new Spu();
        spu.setShopId(shopId);
        spu.setTitle(dto.getTitle());
        spu.setSubtitle(dto.getSubtitle());
        spu.setCategoryId(dto.getCategoryId());
        spu.setBrandId(dto.getBrandId());
        spu.setMainImage(dto.getMainImage());
        spu.setDescription(dto.getDescription());
        spu.setSaleCount(0);
        return spu;
    }

    private ShopInfo getShop(Long shopId) {
        R<ShopInfo> result = shopFeignClient.getShopById(shopId);
        return result == null ? null : result.getData();
    }

    /** 取店铺名（空安全，带 5 分钟 Redis 缓存）：店铺不存在/查询失败返回 null 且不抛出。 */
    private String shopNameOf(Long shopId) {
        if (shopId == null) {
            return null;
        }
        String cached = readShopNameCache(shopId);
        if (cached != null) {
            return cached;
        }
        String name = null;
        try {
            ShopInfo shop = getShop(shopId);
            name = shop == null ? null : shop.getShopName();
        } catch (Exception e) {
            // 失败不入缓存：允许下次调用重试（异步 ES 刷新路径中 Feign 失败不应外抛影响任何事务）
            log.warn("获取店铺名失败（不入缓存，下次重试）：shopId={}, err={}", shopId, e.getMessage());
        }
        if (name != null) {
            writeShopNameCache(shopId, name);
        }
        return name;
    }

    /** 读店名缓存（Redis 共享，多实例一致）；Redis 不可用时 fail-open 返回 null，由调用方直连 shop 服务。 */
    private String readShopNameCache(Long shopId) {
        try {
            return stringRedisTemplate.opsForValue().get(SHOP_NAME_CACHE_PREFIX + shopId);
        } catch (Exception e) {
            log.warn("读取店名缓存失败（降级直连店铺服务）：shopId={}, err={}", shopId, e.getMessage());
            return null;
        }
    }

    /** 写店名缓存（TTL 5 分钟）；写失败仅告警，不影响主流程。 */
    private void writeShopNameCache(Long shopId, String name) {
        try {
            stringRedisTemplate.opsForValue()
                    .set(SHOP_NAME_CACHE_PREFIX + shopId, name, Duration.ofMillis(SHOP_NAME_TTL_MS));
        } catch (Exception e) {
            log.warn("写入店名缓存失败（不影响主流程）：shopId={}, err={}", shopId, e.getMessage());
        }
    }

    /** 重建 SKU：删除旧 SKU 及其图片，按草稿插入新 SKU 并同步库存 */
    private void rebuildSkus(Long spuId, Long shopId, List<SkuDTO> skuDtos) {
        List<Long> oldSkuIds = skuMapper.selectList(
                        Wrappers.<Sku>lambdaQuery().eq(Sku::getSpuId, spuId))
                .stream().map(Sku::getId).toList();
        if (!CollectionUtils.isEmpty(oldSkuIds)) {
            skuImageMapper.delete(Wrappers.<SkuImage>lambdaQuery().in(SkuImage::getSkuId, oldSkuIds));
        }
        skuMapper.delete(Wrappers.<Sku>lambdaQuery().eq(Sku::getSpuId, spuId));
        wareFeignClient.syncStock(insertSkus(spuId, shopId, skuDtos));
        recalcMinPrice(spuId);
    }

    /** 批量插入 SKU 及其图片，返回库存同步条目 */
    private List<StockSyncItem> insertSkus(Long spuId, Long shopId, List<SkuDTO> skuDtos) {
        List<StockSyncItem> stockItems = new ArrayList<>();
        for (SkuDTO dto : skuDtos) {
            Sku sku = new Sku();
            sku.setSpuId(spuId);
            sku.setShopId(shopId);
            sku.setTitle(dto.getTitle());
            sku.setPrice(dto.getPrice());
            // 库存语义：NULL = 不限量；0..N = 限量可售（0=售罄）
            sku.setStock(dto.getStock());
            sku.setSaleCount(0);
            sku.setStatus(SPU_OFF);
            skuMapper.insert(sku);

            StockSyncItem item = new StockSyncItem();
            item.setSpuId(spuId);
            item.setSkuId(sku.getId());
            item.setShopId(shopId);
            item.setTitle(sku.getTitle());
            item.setStock(sku.getStock());
            stockItems.add(item);

            if (dto.getImages() != null) {
                int sort = 0;
                for (String url : dto.getImages()) {
                    SkuImage image = new SkuImage();
                    image.setSkuId(sku.getId());
                    image.setImgUrl(url);
                    image.setSort(sort++);
                    skuImageMapper.insert(image);
                }
            }
        }
        return stockItems;
    }

    /** 收集某 SPU 当前全部 SKU 的库存同步条目 */
    private List<StockSyncItem> collectStockSyncItems(Long spuId, Long shopId) {
        return skuMapper.selectList(Wrappers.<Sku>lambdaQuery().eq(Sku::getSpuId, spuId))
                .stream()
                .map(sku -> {
                    StockSyncItem item = new StockSyncItem();
                    item.setSpuId(spuId);
                    item.setSkuId(sku.getId());
                    item.setShopId(shopId);
                    item.setTitle(sku.getTitle());
                    item.setStock(sku.getStock());
                    return item;
                })
                .toList();
    }

    private String toJson(SpuDTO dto) {
        try {
            return objectMapper.writeValueAsString(dto);
        } catch (JsonProcessingException e) {
            throw new BizException("内容序列化失败");
        }
    }

    private SpuDTO fromJson(String json) {
        try {
            return objectMapper.readValue(json, SpuDTO.class);
        } catch (JsonProcessingException e) {
            throw new BizException("草稿内容解析失败");
        }
    }

    /** 获取当前用户已启用的店铺 */
    private ShopInfo requireEnabledShop(Long userId) {
        if (userId == null) {
            throw new BizException(ResultCode.UNAUTHORIZED.getCode(), "未登录或登录已过期");
        }
        R<ShopInfo> result = shopFeignClient.getShopByUserId(userId);
        ShopInfo shop = result == null ? null : result.getData();
        if (shop == null) {
            throw new BizException("请先入驻开店");
        }
        if (shop.getStatus() == null || shop.getStatus() != SHOP_ENABLED) {
            throw new BizException("店铺未启用，无法操作");
        }
        return shop;
    }

    /** 校验商品归属当前店铺 */
    private Spu requireOwnedSpu(ShopInfo shop, Long spuId) {
        Spu spu = spuMapper.selectById(spuId);
        if (spu == null) {
            throw new BizException("商品不存在");
        }
        if (!spu.getShopId().equals(shop.getId())) {
            throw new BizException(ResultCode.FORBIDDEN.getCode(), "无权操作该商品");
        }
        return spu;
    }

    /** 当前用户是否为某店铺店主（经 Feign 取当前用户店铺后比对 shopId；异常按非属主处理，安全回退）。 */
    private boolean isShopOwner(Long userId, Long shopId) {
        if (userId == null || shopId == null) {
            return false;
        }
        try {
            R<ShopInfo> result = shopFeignClient.getShopByUserId(userId);
            ShopInfo shop = result == null ? null : result.getData();
            return shop != null && Objects.equals(shop.getId(), shopId);
        } catch (Exception e) {
            log.warn("校验店铺归属失败（按非属主处理）：userId={}, shopId={}, err={}", userId, shopId, e.getMessage());
            return false;
        }
    }

    /** 组装 ES 检索文档 */
    private EsProduct buildEsDoc(Spu spu, String shopName) {
        EsProduct doc = new EsProduct();
        doc.setSpuId(spu.getId().toString());
        doc.setShopId(spu.getShopId());
        doc.setShopName(shopName);
        doc.setTitle(spu.getTitle());
        doc.setSubtitle(spu.getSubtitle());
        doc.setCategoryId(spu.getCategoryId());
        doc.setCategoryName(Optional.ofNullable(spu.getCategoryId())
                .map(categoryMapper::selectById).map(Category::getName).orElse(null));
        doc.setBrandId(spu.getBrandId());
        doc.setBrandName(Optional.ofNullable(spu.getBrandId())
                .map(brandMapper::selectById).map(Brand::getName).orElse(null));
        doc.setPrice(priceOf(spu));
        doc.setMainImage(spu.getMainImage());
        doc.setStatus(spu.getStatus());
        doc.setSaleCount(spu.getSaleCount());
        return doc;
    }

    /** 商品最低 SKU 价格 */
    private BigDecimal minSkuPrice(Long spuId) {
        return skuMapper.selectList(Wrappers.<Sku>lambdaQuery().eq(Sku::getSpuId, spuId))
                .stream()
                .map(Sku::getPrice)
                .filter(Objects::nonNull)
                .min(BigDecimal::compareTo)
                .orElse(null);
    }

    /**
     * 读取商品最低价：优先用 SPU 冗余字段（避免每次全量计算 SKU），
     * 老数据未维护该字段时回退为实时计算，保证读一致性。
     */
    private BigDecimal priceOf(Spu spu) {
        return spu.getMinPrice() != null ? spu.getMinPrice() : minSkuPrice(spu.getId());
    }

    /** 按当前 SKU 价格重算并回写 SPU 冗余最低价（SKU 增删/审核生效后调用） */
    private void recalcMinPrice(Long spuId) {
        BigDecimal min = minSkuPrice(spuId);
        Spu update = new Spu();
        update.setId(spuId);
        update.setMinPrice(min);
        spuMapper.updateById(update);
    }

    private ProductVO toProductVO(Spu spu) {
        ProductVO vo = new ProductVO();
        vo.setSpuId(spu.getId());
        vo.setTitle(spu.getTitle());
        vo.setMainImage(spu.getMainImage());
        vo.setPrice(priceOf(spu));
        vo.setSaleCount(spu.getSaleCount());
        vo.setStatus(spu.getStatus());
        vo.setReviewStatus(spu.getReviewStatus());
        return vo;
    }

    /**
     * 批量装配审核 VO：一次批量 Feign 取回当页涉及的全部店铺（旧写法每行一次 HTTP，
     * 一页 10~20 行就是 10~20 次 RPC，店铺服务抖动时整页审核列表变慢）。
     */
    private List<ProductReviewVO> toReviewVOList(List<Spu> spus) {
        if (spus == null || spus.isEmpty()) {
            return List.of();
        }
        Map<Long, ShopInfo> shopById = loadShopsById(spus.stream()
                .map(Spu::getShopId).filter(Objects::nonNull).distinct().toList());
        return spus.stream().map(spu -> toReviewVO(spu, shopById.get(spu.getShopId()))).toList();
    }

    /** 批量查店铺：走 shop 的 /internal/shop/batch；失败降级为空表（店铺名缺失但不影响审核主体信息）。 */
    private Map<Long, ShopInfo> loadShopsById(List<Long> shopIds) {
        if (shopIds.isEmpty()) {
            return Map.of();
        }
        List<ShopInfo> shops = R.checkOk(shopFeignClient.getShopsByIds(shopIds));
        if (shops == null || shops.isEmpty()) {
            return Map.of();
        }
        return shops.stream()
                .filter(shop -> shop.getId() != null)
                .collect(Collectors.toMap(ShopInfo::getId, shop -> shop, (a, b) -> a));
    }

    private ProductReviewVO toReviewVO(Spu spu, ShopInfo shop) {
        ProductReviewVO vo = new ProductReviewVO();
        vo.setSpuId(spu.getId());
        vo.setShopId(spu.getShopId());
        vo.setShopName(shop == null ? null : shop.getShopName());
        vo.setTitle(spu.getTitle());
        vo.setMainImage(spu.getMainImage());
        vo.setPrice(priceOf(spu));
        vo.setStatus(spu.getStatus());
        vo.setReviewStatus(spu.getReviewStatus());
        vo.setDraftContent(spu.getDraftContent());
        vo.setApplicationId(spu.getApplicationId());
        vo.setCreateTime(spu.getCreateTime());
        return vo;
    }

    private ProductDetailVO toDetailVO(Spu spu) {
        ProductDetailVO vo = new ProductDetailVO();
        vo.setSpuId(spu.getId());
        vo.setShopId(spu.getShopId());
        R<ShopInfo> shopResult = shopFeignClient.getShopById(spu.getShopId());
        if (shopResult != null && shopResult.getData() != null) {
            vo.setShopName(shopResult.getData().getShopName());
        }
        vo.setTitle(spu.getTitle());
        vo.setSubtitle(spu.getSubtitle());
        vo.setCategoryId(spu.getCategoryId());
        vo.setCategoryName(Optional.ofNullable(spu.getCategoryId())
                .map(categoryMapper::selectById).map(Category::getName).orElse(null));
        vo.setBrandId(spu.getBrandId());
        vo.setBrandName(Optional.ofNullable(spu.getBrandId())
                .map(brandMapper::selectById).map(Brand::getName).orElse(null));
        vo.setMainImage(spu.getMainImage());
        vo.setDescription(spu.getDescription());
        vo.setStatus(spu.getStatus());
        vo.setReviewStatus(spu.getReviewStatus());
        vo.setSaleCount(spu.getSaleCount());
        vo.setPrice(priceOf(spu));
        // 详情内嵌 SKU 列表设上限，超大 SKU 集合经 /api/product/{id}/skus 分页接口加载
        List<Sku> skus = skuMapper.selectList(Wrappers.<Sku>lambdaQuery()
                .eq(Sku::getSpuId, spu.getId())
                .last("LIMIT 100"));
        vo.setSkus(toSkuDetailVOList(skus));
        return vo;
    }

    private SkuDetailVO toSkuDetailVO(Sku sku, List<String> images) {
        SkuDetailVO vo = new SkuDetailVO();
        vo.setId(sku.getId());
        vo.setTitle(sku.getTitle());
        vo.setPrice(sku.getPrice());
        vo.setStock(sku.getStock());
        vo.setSaleCount(sku.getSaleCount());
        vo.setStatus(sku.getStatus());
        vo.setImages(images);
        return vo;
    }

    /**
     * 批量装载 SKU 图片：一次 {@code in(skuIds)} 取回后按 skuId 分组（旧写法每个 SKU 一次查询，
     * 详情页内嵌 50 个规格就是 50 次往返）。
     */
    private Map<Long, List<String>> loadSkuImages(List<Sku> skus) {
        List<Long> skuIds = skus.stream().map(Sku::getId).filter(Objects::nonNull).toList();
        if (skuIds.isEmpty()) {
            return Map.of();
        }
        return skuImageMapper.selectList(Wrappers.<SkuImage>lambdaQuery().in(SkuImage::getSkuId, skuIds))
                .stream()
                .sorted(Comparator.comparing(SkuImage::getSort))
                .collect(Collectors.groupingBy(SkuImage::getSkuId,
                        Collectors.mapping(SkuImage::getImgUrl, Collectors.toList())));
    }

    /** 按批量图片表装配 SKU 详情列表。 */
    private List<SkuDetailVO> toSkuDetailVOList(List<Sku> skus) {
        if (skus == null || skus.isEmpty()) {
            return List.of();
        }
        Map<Long, List<String>> imagesBySku = loadSkuImages(skus);
        return skus.stream()
                .map(sku -> toSkuDetailVO(sku, imagesBySku.getOrDefault(sku.getId(), List.of())))
                .toList();
    }

    // ==================== 实时数量（剩余件数 / 不限量） ====================

    /**
     * 为 SKU 详情列表填充实时数量与不限量标记（限量的 SKU 回源仓储可用量，失败降级为声明量）。
     *
     * <p>约定：声明量（product.sku.stock）为 NULL = 不限量，展示不计件数；
     * 有限量时展示实时剩余 = 仓储 stock - locked_stock（最小 0，0 = 售罄）。</p>
     *
     * @param skus SKU 详情列表（可原地改写：分页接口新建对象；详情接口已克隆）
     */
    private void attachLiveStock(List<SkuDetailVO> skus) {
        if (skus == null || skus.isEmpty()) {
            return;
        }
        List<SkuDetailVO> limited = new ArrayList<>();
        for (SkuDetailVO s : skus) {
            boolean unlimited = s.getStock() == null;
            s.setUnlimited(unlimited);
            if (unlimited) {
                s.setStock(null);
            } else {
                limited.add(s);
            }
        }
        if (limited.isEmpty()) {
            return;
        }
        Map<Long, Integer> live = fetchLiveStock(
                limited.stream().map(SkuDetailVO::getId).filter(Objects::nonNull).toList());
        for (SkuDetailVO s : limited) {
            int declared = s.getStock() == null ? 0 : s.getStock();
            Integer avail = live.get(s.getId());
            s.setStock(Math.max(avail == null ? declared : avail, 0));
        }
    }

    /**
     * 批量回源仓储实时可用量（stock - locked_stock）。仓储不可用或缺失时返回空表，由调用方回退声明量。
     */
    private Map<Long, Integer> fetchLiveStock(List<Long> skuIds) {
        Map<Long, Integer> map = new HashMap<>();
        if (skuIds == null || skuIds.isEmpty()) {
            return map;
        }
        try {
            R<List<WareStockInfo>> r = wareFeignClient.listStock(skuIds);
            if (r != null && r.getData() != null) {
                for (WareStockInfo info : r.getData()) {
                    if (info.getSkuId() == null || Integer.valueOf(1).equals(info.getUnlimited())) {
                        continue; // 仓库判定不限量则无「可用」概念，跳过
                    }
                    int stock = info.getStock() == null ? 0 : info.getStock();
                    int locked = info.getLockedStock() == null ? 0 : info.getLockedStock();
                    map.put(info.getSkuId(), stock - locked);
                }
            }
        } catch (Exception e) {
            log.warn("回源仓储实时库存失败，降级为声明数量：skuIds={}, err={}", skuIds, e.getMessage());
        }
        return map;
    }

    /**
     * 卖家「我的商品」列表填充各 SKU 实时数量（不限量/剩余件数）。
     */
    private void fillMyProductStock(List<ProductVO> vos) {
        if (vos == null || vos.isEmpty()) {
            return;
        }
        List<Long> spuIds = vos.stream().map(ProductVO::getSpuId).filter(Objects::nonNull).distinct().toList();
        List<Sku> allSkus = skuMapper.selectList(Wrappers.<Sku>lambdaQuery().in(Sku::getSpuId, spuIds));
        Map<Long, List<Sku>> bySpu = allSkus.stream()
                .collect(Collectors.groupingBy(Sku::getSpuId));
        // 一次批量回源全部限量 SKU 的实时可用量
        List<Long> limitedIds = allSkus.stream()
                .filter(s -> s.getStock() != null)
                .map(Sku::getId)
                .filter(Objects::nonNull)
                .toList();
        Map<Long, Integer> live = fetchLiveStock(limitedIds);

        for (ProductVO vo : vos) {
            List<Sku> items = bySpu.getOrDefault(vo.getSpuId(), List.of());
            vo.setStocks(items.stream().map(s -> {
                SkuStockVO sv = new SkuStockVO();
                sv.setSkuId(s.getId());
                sv.setTitle(s.getTitle());
                boolean unlimited = s.getStock() == null;
                sv.setUnlimited(unlimited);
                if (unlimited) {
                    sv.setAvailable(null);
                } else {
                    int declared = s.getStock();
                    Integer avail = live.get(s.getId());
                    sv.setAvailable(Math.max(avail == null ? declared : avail, 0));
                }
                return sv;
            }).toList());
        }
    }
}
