package com.lingmart.shop.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lingmart.common.constant.AuthConstants;
import com.lingmart.common.dto.ShopInfo;
import com.lingmart.common.dto.ShopProductItem;
import com.lingmart.common.dto.UserInfo;
import com.lingmart.common.dto.UserRoleUpdateRequest;
import com.lingmart.common.exception.BizException;
import com.lingmart.common.result.PageResult;
import com.lingmart.common.result.R;
import com.lingmart.common.result.ResultCode;
import com.lingmart.common.util.AuthUtils;
import com.lingmart.shop.dto.ApplyShopDTO;
import com.lingmart.shop.dto.ReviewApplicationDTO;
import com.lingmart.shop.dto.ShopDraftDTO;
import com.lingmart.shop.entity.AuditLog;
import com.lingmart.shop.entity.Shop;
import com.lingmart.shop.entity.ShopApplication;
import com.lingmart.shop.feign.AuthFeignClient;
import com.lingmart.shop.feign.ProductFeignClient;
import com.lingmart.shop.mapper.AuditLogMapper;
import com.lingmart.shop.mapper.ShopApplicationMapper;
import com.lingmart.shop.mapper.ShopMapper;
import com.lingmart.shop.service.ShopService;
import com.lingmart.shop.vo.ApplicationVO;
import com.lingmart.shop.vo.ShopStorefrontVO;
import com.lingmart.shop.vo.ShopVO;
import io.seata.spring.annotation.GlobalTransactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Objects;

/**
 * 店铺服务实现（审核制模型）。
 *
 * <p>核心闭环：提交入驻申请（建店 + 初始商品待审核）→ 管理员审核 → 通过则启用店铺、
 * 关联商品通过并上架、提升用户角色为卖家；拒绝则整体拒绝。
 * 改店铺信息走宽松重审（旧信息照常营业，新信息审核通过才生效）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ShopServiceImpl implements ShopService {

    /** 营业状态：未启用 / 营业中 / 已禁用 */
    public static final int SHOP_PENDING = 0;
    public static final int SHOP_ENABLED = 1;
    public static final int SHOP_DISABLED = 2;
    /** 审核状态：待审核 / 已通过 / 已拒绝 / 待重审 */
    public static final int REVIEW_PENDING = 0;
    public static final int REVIEW_APPROVED = 1;
    public static final int REVIEW_REJECTED = 2;
    public static final int REVIEW_REVIEWING = 3;
    /** 申请状态：待审核 / 已通过 / 已拒绝 */
    public static final int APPLY_PENDING = 0;
    public static final int APPLY_APPROVED = 1;
    public static final int APPLY_REJECTED = 2;

    private final ShopMapper shopMapper;
    private final ShopApplicationMapper applicationMapper;
    private final AuditLogMapper auditLogMapper;
    private final AuthFeignClient authFeignClient;
    private final ProductFeignClient productFeignClient;
    private final ObjectMapper objectMapper;

    @Override
    @GlobalTransactional(name = "applyShop", rollbackFor = Exception.class)
    @Transactional(rollbackFor = Exception.class)
    public void apply(Long userId, ApplyShopDTO dto) {
        requireUserId(userId);
        if (dto.getProducts() == null || dto.getProducts().isEmpty()) {
            throw new BizException("开店申请必须携带要卖的商品");
        }
        // 三态校验：营业中/已禁用 → 禁止；待审核/待重审 → 审核完成前拒绝再次申请；被拒绝 → 复用原店铺行重新申请
        Shop shop = shopMapper.selectOne(Wrappers.<Shop>lambdaQuery().eq(Shop::getUserId, userId));
        Long shopId;
        if (shop != null) {
            if (Objects.equals(shop.getStatus(), SHOP_ENABLED)) {
                throw new BizException("您已是卖家，无法重复申请开店");
            }
            if (Objects.equals(shop.getStatus(), SHOP_DISABLED)) {
                throw new BizException("店铺已被禁用，无法重新申请");
            }
            if (Objects.equals(shop.getReviewStatus(), REVIEW_PENDING)
                    || Objects.equals(shop.getReviewStatus(), REVIEW_REVIEWING)) {
                throw new BizException("已有开店申请审核中，请耐心等待审核结果");
            }
            // 被拒绝（REVIEW_REJECTED）：复用原 shop 行，重置为待审核，避免无限堆店铺行
            Shop update = new Shop();
            update.setId(shop.getId());
            update.setShopName(dto.getShopName());
            update.setShopLogo(dto.getShopLogo());
            update.setDescription(dto.getDescription());
            update.setStatus(SHOP_PENDING);
            update.setReviewStatus(REVIEW_PENDING);
            update.setDraftContent(null);
            shopMapper.updateById(update);
            shopId = shop.getId();
        } else {
            // 首次申请：创建店铺（未启用 + 待审核）
            Shop created = new Shop();
            created.setUserId(userId);
            created.setShopName(dto.getShopName());
            created.setShopLogo(dto.getShopLogo());
            created.setDescription(dto.getDescription());
            created.setStatus(SHOP_PENDING);
            created.setReviewStatus(REVIEW_PENDING);
            try {
                shopMapper.insert(created);
                shopId = created.getId();
            } catch (DuplicateKeyException e) {
                // 并发/双击提交：uk_user_id（一个用户只能有一个店铺）或 uk_shop_name（店名全局唯一）冲突。
                // 若按 userId 能重查出已落库的行，说明是并发提交，复用它继续走申请流程；重查不到则冲突来自店名。
                Shop existShop = shopMapper.selectOne(Wrappers.<Shop>lambdaQuery().eq(Shop::getUserId, userId));
                if (existShop == null) {
                    throw new BizException("店铺名称已被占用，请更换店铺名称后重新提交");
                }
                log.info("并发提交开店申请命中唯一键，复用已存在的店铺行：userId={}, shopId={}", userId, existShop.getId());
                shopId = existShop.getId();
            }
        }

        // 每次申请都新建一条入驻申请（留痕，管理员按最新一条审核）
        ShopApplication app = new ShopApplication();
        app.setUserId(userId);
        app.setShopName(dto.getShopName());
        app.setShopLogo(dto.getShopLogo());
        app.setDescription(dto.getDescription());
        app.setStatus(APPLY_PENDING);
        applicationMapper.insert(app);

        // 申请携带的初始商品 → 商品服务创建为待审核商品（校验返回，避免静默失败）
        R.checkOk(productFeignClient.createForApplication(shopId, app.getId(), dto.getProducts()));
        log.info("商家提交入驻申请：userId={}, shopId={}, shopName={}, products={}",
                userId, shopId, dto.getShopName(), dto.getProducts().size());
    }

    @Override
    public ShopVO getMyShop(Long userId) {
        requireUserId(userId);
        Shop shop = shopMapper.selectOne(Wrappers.<Shop>lambdaQuery().eq(Shop::getUserId, userId));
        if (shop == null) {
            throw new BizException("暂无店铺，请先提交入驻申请");
        }
        return toShopVO(shop);
    }

    @Override
    public void updateMyShop(Long userId, ApplyShopDTO dto) {
        requireUserId(userId);
        Shop shop = shopMapper.selectOne(Wrappers.<Shop>lambdaQuery().eq(Shop::getUserId, userId));
        if (shop == null) {
            throw new BizException("暂无店铺");
        }
        if (shop.getStatus() == null || shop.getStatus() != SHOP_ENABLED) {
            throw new BizException("店铺未营业，无法编辑");
        }
        // 宽松重审：新信息写入草稿，review_status=待重审，旧信息照常营业
        ShopDraftDTO draft = new ShopDraftDTO();
        draft.setShopName(dto.getShopName());
        draft.setShopLogo(dto.getShopLogo());
        draft.setDescription(dto.getDescription());
        Shop update = new Shop();
        update.setId(shop.getId());
        update.setReviewStatus(REVIEW_REVIEWING);
        update.setDraftContent(toJson(draft));
        shopMapper.updateById(update);
        log.info("店铺信息变更进入待重审：shopId={}, userId={}", shop.getId(), userId);
    }

    @Override
    public ApplicationVO getMyApplication(Long userId) {
        requireUserId(userId);
        ShopApplication app = applicationMapper.selectOne(
                Wrappers.<ShopApplication>lambdaQuery()
                        .eq(ShopApplication::getUserId, userId)
                        .orderByDesc(ShopApplication::getCreateTime)
                        .last("LIMIT 1"));
        // 无申请时返回 null（前端据此展示申请表，而非报错提示）
        if (app == null) {
            return null;
        }
        return toApplicationVO(app);
    }

    @Override
    public PageResult<ApplicationVO> listApplications(String role, Integer status, long page, long size) {
        AuthUtils.requireRole(role, AuthConstants.ROLE_ADMIN);
        Page<ShopApplication> p = applicationMapper.selectPage(new Page<>(page, size),
                Wrappers.<ShopApplication>lambdaQuery()
                        .eq(status != null, ShopApplication::getStatus, status)
                        .orderByDesc(ShopApplication::getCreateTime));
        return PageResult.of(p.getRecords().stream().map(this::toApplicationVO).toList(),
                p.getTotal(), page, size);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void review(String role, Long operatorId, Long applicationId, ReviewApplicationDTO dto) {
        AuthUtils.requireRole(role, AuthConstants.ROLE_ADMIN);
        ShopApplication app = applicationMapper.selectById(applicationId);
        if (app == null) {
            throw new BizException("申请不存在");
        }
        if (app.getStatus() != APPLY_PENDING) {
            throw new BizException("该申请已审核");
        }

        ShopApplication updateApp = new ShopApplication();
        updateApp.setId(app.getId());
        if (Boolean.TRUE.equals(dto.getApprove())) {
            // 通过：申请通过 + 店铺营业 + 商品全部通过上架 + 提升角色
            updateApp.setStatus(APPLY_APPROVED);
            applicationMapper.updateById(updateApp);

            Shop update = new Shop();
            update.setStatus(SHOP_ENABLED);
            update.setReviewStatus(REVIEW_APPROVED);
            shopMapper.update(update, Wrappers.<Shop>lambdaUpdate().eq(Shop::getUserId, app.getUserId()));
            // 传入店铺名，商品服务写入 ES 索引时无需在全局事务内回调本服务
            R.checkOk(productFeignClient.approveByApplication(applicationId, app.getShopName()));

            // 提升卖家角色：auth 未纳入 Seata（独立库无 undo_log），失败只记录日志不阻断审核，
            // 避免「审核通过」被一个旁路角色更新拖成系统繁忙（角色可由管理员手工修正）
            UserRoleUpdateRequest req = new UserRoleUpdateRequest();
            req.setUserId(app.getUserId());
            req.setRole(AuthConstants.ROLE_SELLER);
            try {
                R.checkOk(authFeignClient.updateRole(req));
            } catch (Exception e) {
                log.error("入驻审核通过但角色提升失败（需人工修正）：userId={}, err={}",
                        app.getUserId(), e.getMessage());
            }
            recordAudit(operatorId, "APPLY_REVIEW", applicationId, 1, null);
            log.info("入驻审核通过：applicationId={}, userId={}", applicationId, app.getUserId());
        } else {
            // 拒绝：申请拒绝 + 商品拒绝 + 店铺禁用
            updateApp.setStatus(APPLY_REJECTED);
            updateApp.setRejectReason(StringUtils.hasText(dto.getRejectReason()) ? dto.getRejectReason() : "不符合入驻要求");
            applicationMapper.updateById(updateApp);

            Shop update = new Shop();
            update.setReviewStatus(REVIEW_REJECTED);
            shopMapper.update(update, Wrappers.<Shop>lambdaUpdate().eq(Shop::getUserId, app.getUserId()));
            R.checkOk(productFeignClient.rejectByApplication(applicationId));
            recordAudit(operatorId, "APPLY_REVIEW", applicationId, 0, updateApp.getRejectReason());
            log.info("入驻审核拒绝：applicationId={}, userId={}, reason={}",
                    applicationId, app.getUserId(), updateApp.getRejectReason());
        }
    }

    @Override
    public PageResult<ShopVO> listShops(String role, Integer reviewStatus, long page, long size) {
        AuthUtils.requireRole(role, AuthConstants.ROLE_ADMIN);
        Page<Shop> p = shopMapper.selectPage(new Page<>(page, size),
                Wrappers.<Shop>lambdaQuery()
                        .eq(reviewStatus != null, Shop::getReviewStatus, reviewStatus)
                        .orderByDesc(Shop::getCreateTime));
        return PageResult.of(p.getRecords().stream().map(this::toShopVO).toList(),
                p.getTotal(), page, size);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void reviewShop(String role, Long operatorId, Long shopId, ReviewApplicationDTO dto) {
        AuthUtils.requireRole(role, AuthConstants.ROLE_ADMIN);
        Shop shop = shopMapper.selectById(shopId);
        if (shop == null) {
            throw new BizException("店铺不存在");
        }
        if (!Objects.equals(shop.getReviewStatus(), REVIEW_REVIEWING)) {
            throw new BizException("该店铺没有待审核的信息变更");
        }
        Shop update = new Shop();
        update.setId(shopId);
        // 本次审核后生效的新店名（仅通过分支设置；仅改 logo/描述或草稿无店名时为 null）
        String newShopName = null;
        if (Boolean.TRUE.equals(dto.getApprove())) {
            // 通过：应用草稿新信息
            ShopDraftDTO draft = fromJson(shop.getDraftContent());
            newShopName = draft.getShopName();
            update.setShopName(newShopName);
            update.setShopLogo(draft.getShopLogo());
            update.setDescription(draft.getDescription());
            update.setDraftContent(null);
            update.setReviewStatus(REVIEW_APPROVED);
            recordAudit(operatorId, "SHOP_REVIEW", shopId, 1, null);
            log.info("店铺信息变更审核通过：shopId={}", shopId);
        } else {
            // 拒绝：丢弃草稿，旧信息照常营业
            update.setDraftContent(null);
            update.setReviewStatus(REVIEW_REJECTED);
            recordAudit(operatorId, "SHOP_REVIEW", shopId, 0, dto.getRejectReason());
            log.info("店铺信息变更审核拒绝：shopId={}", shopId);
        }
        shopMapper.updateById(update);
        // 店名是 ES 文档里的快照：改了不同步会让搜索/店铺聚合一直显示旧名（对账任务只补缺失、不重建已存在文档）。
        if (StringUtils.hasText(newShopName) && !newShopName.equals(shop.getShopName())) {
            final String name = newShopName;
            // 重建可能涉及上千个 SPU（每批 200 条 bulk + 分类/品牌查询），不能在事务内等它——否则管理员点一次
            // 「通过」就要持着 shop 行锁等完整个重建过程（改名是低频操作，但没必要把锁交给网络 IO）。
            // 放在「事务提交后、同一线程」执行：Spring 6.2 的 processCommit 明确保证 afterCommit 抛出的异常
            // 会传播给调用方、而事务仍视为已提交。于是 DB 侧改名已生效，接口如实报错并给出补做手段。
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    try {
                        R.checkOk(productFeignClient.reindexShopEs(shopId, name));
                    } catch (Exception e) {
                        log.error("店铺改名后重建 ES 索引失败：shopId={}, newName={}, err={}", shopId, name, e.getMessage());
                        throw new BizException("店名已生效，但 ES 索引重建失败（" + e.getMessage()
                                + "）；可用管理端「重建店铺索引」入口重试：POST /api/admin/product/shop/"
                                + shopId + "/reindex-es");
                    }
                }
            });
        }
    }

    @Override
    public PageResult<AuditLog> auditList(String role, long page, long size) {
        AuthUtils.requireRole(role, AuthConstants.ROLE_ADMIN);
        Page<AuditLog> p = auditLogMapper.selectPage(new Page<>(page, size),
                Wrappers.<AuditLog>lambdaQuery().orderByDesc(AuditLog::getCreateTime));
        return PageResult.of(p.getRecords(), p.getTotal(), page, size);
    }

    /** 写入审核审计日志（best-effort：失败不影响审核结果） */
    private void recordAudit(Long operatorId, String action, Long targetId, int result, String reason) {
        try {
            AuditLog log = new AuditLog();
            log.setOperatorId(operatorId);
            log.setAction(action);
            log.setTargetId(targetId);
            log.setResult(result);
            log.setReason(reason);
            auditLogMapper.insert(log);
        } catch (Exception e) {
            log.warn("审计日志写入失败（不影响审核）：action={}, targetId={}, err={}", action, targetId, e.getMessage());
        }
    }

    @Override
    public ShopInfo getShopByUserId(Long userId) {
        if (userId == null) {
            return null;
        }
        Shop shop = shopMapper.selectOne(Wrappers.<Shop>lambdaQuery().eq(Shop::getUserId, userId));
        return toShopInfo(shop);
    }

    @Override
    public ShopInfo getShopById(Long shopId) {
        if (shopId == null) {
            return null;
        }
        return toShopInfo(shopMapper.selectById(shopId));
    }

    @Override
    public List<ShopInfo> getShopsByIds(List<Long> shopIds) {
        if (shopIds == null || shopIds.isEmpty()) {
            return List.of();
        }
        // 一次 IN 查询取全部店铺，再统一映射为契约对象（未命中 id 自然不在结果里）
        return shopMapper.selectBatchIds(shopIds).stream()
                .map(this::toShopInfo)
                .filter(Objects::nonNull)
                .toList();
    }

    @Override
    public List<ShopInfo> listEnabledShops() {
        // 首页店铺列表：仅营业中（审核已通过）的店铺，公开浏览
        return shopMapper.selectList(Wrappers.<Shop>lambdaQuery()
                        .eq(Shop::getStatus, SHOP_ENABLED)
                        .orderByAsc(Shop::getId))
                .stream()
                .map(this::toShopInfo)
                .toList();
    }

    @Override
    public ShopStorefrontVO getStorefront(Long shopId) {
        Shop shop = shopId == null ? null : shopMapper.selectById(shopId);
        if (shop == null || !Objects.equals(shop.getStatus(), SHOP_ENABLED)) {
            throw new BizException("店铺不存在或未营业");
        }
        ShopStorefrontVO vo = new ShopStorefrontVO();
        vo.setId(shop.getId());
        vo.setShopName(shop.getShopName());
        vo.setShopLogo(shop.getShopLogo());
        vo.setDescription(shop.getDescription());

        // 店主公开信息（仅头像/昵称，手机号不外发——店铺主页不暴露联系方式）
        R<UserInfo> userResult = authFeignClient.getUser(shop.getUserId());
        if (userResult != null && userResult.getData() != null) {
            UserInfo u = userResult.getData();
            ShopStorefrontVO.OwnerInfo owner = new ShopStorefrontVO.OwnerInfo();
            owner.setUserId(u.getId());
            owner.setNickname(u.getNickname());
            owner.setAvatar(u.getAvatar());
            vo.setOwner(owner);
        }

        // 在售商品（经商品服务内部接口聚合）
        R<List<ShopProductItem>> productsResult = productFeignClient.getShopProducts(shop.getId());
        vo.setProducts(productsResult != null && productsResult.getData() != null
                ? productsResult.getData() : List.of());
        log.info("店铺主页访问：shopId={}, products={}", shopId, vo.getProducts().size());
        return vo;
    }

    /** 实体转跨服务契约对象 */
    private ShopInfo toShopInfo(Shop shop) {
        if (shop == null) {
            return null;
        }
        ShopInfo info = new ShopInfo();
        info.setId(shop.getId());
        info.setUserId(shop.getUserId());
        info.setShopName(shop.getShopName());
        info.setShopLogo(shop.getShopLogo());
        info.setStatus(shop.getStatus());
        return info;
    }

    private void requireUserId(Long userId) {
        if (userId == null) {
            throw new BizException(ResultCode.UNAUTHORIZED.getCode(), "未登录或登录已过期");
        }
    }

    private String toJson(ShopDraftDTO dto) {
        try {
            return objectMapper.writeValueAsString(dto);
        } catch (JsonProcessingException e) {
            throw new BizException("内容序列化失败");
        }
    }

    private ShopDraftDTO fromJson(String json) {
        try {
            return objectMapper.readValue(json, ShopDraftDTO.class);
        } catch (JsonProcessingException e) {
            throw new BizException("草稿内容解析失败");
        }
    }

    private ShopVO toShopVO(Shop shop) {
        ShopVO vo = new ShopVO();
        vo.setId(shop.getId());
        vo.setShopName(shop.getShopName());
        vo.setShopLogo(shop.getShopLogo());
        vo.setDescription(shop.getDescription());
        vo.setStatus(shop.getStatus());
        vo.setReviewStatus(shop.getReviewStatus());
        vo.setDraftContent(shop.getDraftContent());
        return vo;
    }

    private ApplicationVO toApplicationVO(ShopApplication app) {
        ApplicationVO vo = new ApplicationVO();
        vo.setId(app.getId());
        vo.setShopName(app.getShopName());
        vo.setDescription(app.getDescription());
        vo.setStatus(app.getStatus());
        vo.setRejectReason(app.getRejectReason());
        vo.setCreateTime(app.getCreateTime());
        return vo;
    }
}
