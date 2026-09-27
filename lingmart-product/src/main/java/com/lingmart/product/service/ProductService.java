package com.lingmart.product.service;

import com.lingmart.common.dto.SaleItem;
import com.lingmart.common.dto.ShopProductItem;
import com.lingmart.common.dto.SkuDTO;
import com.lingmart.common.dto.SpuDTO;
import com.lingmart.common.result.PageResult;
import com.lingmart.product.entity.AuditLog;
import com.lingmart.product.es.EsProduct;
import com.lingmart.product.vo.ProductDetailVO;
import com.lingmart.product.vo.ProductReviewVO;
import com.lingmart.product.vo.ProductVO;
import com.lingmart.product.vo.SkuDetailVO;

import java.util.List;

/**
 * 商品服务业务接口。
 *
 * <p>审核制模型：商品必须通过审核才能上架销售；编辑商品内容进入"待重审"（宽松，
 * 旧内容先卖，新内容存草稿、审核通过才生效）；库存变更不触发重审。</p>
 */
public interface ProductService {

    /**
     * 发布商品（开店后新增商品）→ 审核状态置为待审核，未上架。
     *
     * @param userId 卖家用户ID
     * @param dto    商品参数
     * @return 商品SPU ID
     */
    Long createProduct(Long userId, SpuDTO dto);

    /**
     * 编辑商品内容（宽松重审）：新内容写入草稿，review_status=待重审，旧内容照常销售。
     *
     * @param userId 卖家用户ID
     * @param spuId  商品SPU ID
     * @param dto    新内容
     */
    void updateProduct(Long userId, Long spuId, SpuDTO dto);

    /**
     * 仅修改库存（不触发重审）：直接更新 SKU 库存并同步仓储服务。
     *
     * @param userId 卖家用户ID
     * @param spuId  商品SPU ID
     * @param skus   新库存列表
     */
    void updateStock(Long userId, Long spuId, List<SkuDTO> skus);

    /**
     * 上架（仅审核通过的商品可上架）。
     *
     * @param userId 卖家用户ID
     * @param spuId  商品SPU ID
     */
    void onShelf(Long userId, Long spuId);

    /**
     * 下架。
     *
     * @param userId 卖家用户ID
     * @param spuId  商品SPU ID
     */
    void offShelf(Long userId, Long spuId);

    /**
     * 商品详情（当前生效内容）。
     *
     * @param spuId 商品SPU ID
     * @return 详情
     */
    ProductDetailVO getDetail(Long spuId);

    /**
     * 商品详情（含每个 SKU 的实时剩余件数 / 不限量标记）。
     *
     * <p>静态内容走 {@link #getDetail} 缓存；实时数量单独回源仓储并克隆返回，
     * 避免污染缓存实例。仓储不可用时降级展示卖家声明数量。</p>
     *
     * @param spuId 商品SPU ID
     * @return 详情；商品不存在返回 null
     */
    ProductDetailVO getDetailWithStock(Long spuId);

    /**
     * 商品详情（对外查看，带可见性门禁）。
     *
     * <p>仅「上架且审核通过」的商品对所有人可见；未上架/待审核/已拒绝/已下架的私有商品
     * 只允许其属主（卖家）预览。非属主访问非公开商品与“商品不存在”同语义（返回 null），
     * 避免通过枚举 spuId 探测未发布商品。</p>
     *
     * @param spuId 商品SPU ID
     * @param userId 当前用户 ID（网关透传，可为空=未登录）
     * @return 详情；不可见或不存在返回 null
     */
    ProductDetailVO detailForView(Long spuId, Long userId);

    /**
     * 商品 SKU 分页（对外查看，可见性规则同 {@link #detailForView}）。
     *
     * @param spuId 商品SPU ID
     * @param userId 当前用户 ID（可为空=未登录）
     * @param page  页码（从 1 起）
     * @param size  每页大小
     * @return 分页 SKU 列表；无权查看时抛「商品不存在」
     */
    PageResult<SkuDetailVO> pageSkusForView(Long spuId, Long userId, long page, long size);

    /**
     * 商品 SKU 分页（详情页 SKU 数量大时分页加载，避免一次拉全）。
     *
     * @param spuId 商品SPU ID
     * @param page  页码（从 1 起）
     * @param size  每页大小
     * @return 分页 SKU 列表
     */
    PageResult<SkuDetailVO> pageSkus(Long spuId, long page, long size);

    /**
     * 卖家我的商品列表（含审核状态，分页）。
     *
     * @param userId       卖家用户ID
     * @param page         页码（从 1 起）
     * @param size         每页大小
     * @param status       可选，按上下架状态筛选：0 已下架，1 已上架；null=不筛选
     * @param reviewStatus 可选，按审核状态筛选：0 待审核，1 已通过，2 已拒绝，3 待重审；null=不筛选
     * @return 分页商品列表
     */
    PageResult<ProductVO> myProducts(Long userId, long page, long size, Integer status, Integer reviewStatus);

    /**
     * 在售商品列表（审核通过 + 上架，分页）。
     *
     * @param page 页码（从 1 起）
     * @param size 每页大小
     * @return 分页商品列表
     */
    PageResult<ProductVO> listOnShelf(long page, long size);

    /**
     * 开店申请携带的初始商品：批量创建为待审核商品（内部接口，shop 服务调用）。
     *
     * @param shopId       店铺ID
     * @param applicationId 入驻申请ID
     * @param products     商品清单
     * @return 创建的 SPU ID 列表
     */
    List<Long> createProductsForApplication(Long shopId, Long applicationId, List<SpuDTO> products);

    /**
     * 按入驻申请审核通过：申请关联的全部商品置为已通过并上架（内部接口，shop 服务调用）。
     *
     * <p>传入 {@code shopName} 供 ES 文档写入店铺名，避免在 Seata 全局事务内反向 Feign 回调 shop 服务
     * （事务根回调自身的分支是 Seata 雷区，易触发「系统繁忙」）。</p>
     *
     * @param applicationId 入驻申请ID
     * @param shopName      店铺名称（可能为 null，写入 ES 时容错）
     */
    void approveByApplication(Long applicationId, String shopName);

    /**
     * 按入驻申请审核拒绝：申请关联的全部商品置为已拒绝（内部接口，shop 服务调用）。
     *
     * @param applicationId 入驻申请ID
     */
    void rejectByApplication(Long applicationId);

    /**
     * 管理员：按审核状态查询待审商品列表（分页）。
     *
     * @param reviewStatus 审核状态（0待审核 / 3待重审）
     * @param page         页码（从 1 起）
     * @param size         每页大小
     * @return 分页待审商品
     */
    PageResult<ProductReviewVO> reviewList(Integer reviewStatus, long page, long size);

    /**
     * 管理员：审核单个商品（通过 / 拒绝），结果写入审计日志。
     *
     * @param operatorId   操作人用户ID（审计）
     * @param spuId        商品SPU ID
     * @param approve      是否通过
     * @param rejectReason 拒绝原因
     */
    void reviewProduct(Long operatorId, Long spuId, boolean approve, String rejectReason);

    /**
     * 管理员：批量审核商品（通过 / 拒绝），逐条写审计日志。
     *
     * @param operatorId   操作人用户ID（审计）
     * @param spuIds       商品SPU ID 列表
     * @param approve      是否通过
     * @param rejectReason 拒绝原因
     */
    void reviewProducts(Long operatorId, List<Long> spuIds, boolean approve, String rejectReason);

    /**
     * 审核审计日志列表（管理员，分页）。
     *
     * @param role 当前用户角色（须为 ROLE_ADMIN）
     * @param page 页码
     * @param size 每页大小
     * @return 分页审计日志
     */
    PageResult<AuditLog> auditList(String role, long page, long size);

    /**
     * 按入驻申请查询其携带的商品（管理员审申请时展示，含审核状态）。
     *
     * @param applicationId 入驻申请ID
     * @return 商品审核视图列表
     */
    List<ProductReviewVO> reviewListByApplication(Long applicationId);

    /**
     * 累加销量（支付成功后 order 服务经 Feign 调用，内部接口）。
     *
     * @param items 销量条目
     */
    void updateSale(List<SaleItem> items);

    /**
     * 异步刷新销量到 ES（供「销量更新事件」的监听器在事务提交后调用）。
     *
     * <p>与 {@link #updateSale} 解耦：销量 DB 累加仍在支付全局事务内同步完成；
     * ES 销量刷新移到异步（ES 最终一致可接受），避免 ES/店名 Feign 网络 IO 拖住支付事务与 SPU 行锁。
     * 内部已做 fail-soft（EsProductService 失败落 es_write_retry 补偿表），此处再兜一层异常防漏。</p>
     *
     * @param items 本次支付销量条目
     */
    void refreshEsSalesIndex(List<SaleItem> items);

    /**
     * 回退销量（退款审核通过后 order 服务经 Feign 调用，内部接口）。
     *
     * <p>与 {@link #updateSale} 严格分离：累加路径保持「只增不减」，回退路径用
     * {@code GREATEST(sale_count - n, 0)} 保证不会出现负销量。回退后同样发布
     * {@link com.lingmart.product.event.SaleUpdateEvent} 异步刷新 ES 销量。</p>
     *
     * <p>幂等：以 {@code orderSn} 为键（sale_rollback_log 唯一约束），同一订单重复调用只回退一次，
     * 避免「已回退但响应超时 → 全局事务回滚 → 卖家重试」造成二次扣减。</p>
     *
     * @param orderSn 订单号（幂等键；为空时不做幂等保护）
     * @param items   销量条目（num 取绝对值作为回退量）
     */
    void rollbackSale(String orderSn, List<SaleItem> items);

    /**
     * 重建某店铺在售商品的 ES 文档（店铺改名审核通过后由 shop 服务调用；管理端手动兜底复用）。
     *
     * <p>店名以入参为准（改名场景直接传新店名），并同时覆盖本地店名缓存，避免 5 分钟 TTL 内
     * 其他链路（如支付后的销量刷新）用旧店名重建文档、把改名「吃回去」。ES 写失败由
     * es_write_retry 补偿与定时对账兜底。</p>
     *
     * @param shopId   店铺 ID
     * @param shopName 新店名；为空时经 shop 服务新鲜查询（不走本地缓存）
     * @return 实际重建的文档数
     */
    int refreshEsByShop(Long shopId, String shopName);

    /**
     * 以 DB 为准组装某 SPU 的 ES 文档（供 ES 写失败补偿重放使用）。
     *
     * <p>补偿重放不再重放失败当时的旧快照，而是重查 DB 重建，避免销量回退/店铺改名后
     * 过期数据被写回 ES 覆盖新值。</p>
     *
     * @param spuId 商品 ID
     * @return ES 文档；SPU 不存在或非在售时返回 {@code null}（调用方应删除对应 ES 文档）
     */
    EsProduct loadEsDoc(Long spuId);

    /**
     * 某店铺的在售商品（审核通过 + 上架，供店铺主页展示；内部接口，shop 服务调用）。
     *
     * @param shopId 店铺 ID
     * @return 在售商品条目列表
     */
    List<ShopProductItem> listOnShelfByShop(Long shopId);
}
