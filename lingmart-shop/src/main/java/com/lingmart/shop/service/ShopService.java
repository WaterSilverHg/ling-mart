package com.lingmart.shop.service;

import com.lingmart.common.dto.ShopInfo;
import com.lingmart.common.result.PageResult;
import com.lingmart.shop.dto.ApplyShopDTO;
import com.lingmart.shop.dto.ReviewApplicationDTO;
import com.lingmart.shop.entity.AuditLog;
import com.lingmart.shop.vo.ApplicationVO;
import com.lingmart.shop.vo.ShopFulfillmentVO;
import com.lingmart.shop.vo.ShopStorefrontVO;
import com.lingmart.shop.vo.ShopVO;

import java.util.List;

/**
 * 店铺服务接口。
 */
public interface ShopService {

    /**
     * 提交入驻申请（创建待审核店铺 + 申请记录 + 初始商品，商品进入待审核）。
     *
     * @param userId 当前用户 ID
     * @param dto    申请参数（含要卖的商品清单）
     */
    void apply(Long userId, ApplyShopDTO dto);

    /**
     * 查询我的店铺。
     *
     * @param userId 当前用户 ID
     * @return 店铺信息
     */
    ShopVO getMyShop(Long userId);

    /**
     * 更新我的店铺信息（宽松重审）：新信息写入草稿，review_status=待重审，旧信息照常营业。
     *
     * @param userId 当前用户 ID
     * @param dto    更新参数（忽略 products）
     */
    void updateMyShop(Long userId, ApplyShopDTO dto);

    /**
     * 查询我的入驻申请状态。
     *
     * @param userId 当前用户 ID
     * @return 最新一条申请记录
     */
    ApplicationVO getMyApplication(Long userId);

    /**
     * 入驻申请列表（管理员，分页）。
     *
     * @param role   当前用户角色（须为 ROLE_ADMIN）
     * @param status 按状态过滤，可空
     * @param page   页码（从 1 起）
     * @param size   每页大小
     * @return 分页申请列表
     */
    PageResult<ApplicationVO> listApplications(String role, Integer status, long page, long size);

    /**
     * 审核入驻申请（管理员）：通过则启用店铺、关联商品全部通过并上架、提升用户角色为卖家；
     * 拒绝则申请拒绝、商品拒绝、店铺禁用；审核结果写入审计日志。
     *
     * @param role          当前用户角色（须为 ROLE_ADMIN）
     * @param operatorId    操作人用户ID（审计）
     * @param applicationId 申请 ID
     * @param dto           审核结果
     */
    void review(String role, Long operatorId, Long applicationId, ReviewApplicationDTO dto);

    /**
     * 店铺列表（管理员，按审核状态过滤，用于描述变更重审；分页）。
     *
     * @param role         当前用户角色（须为 ROLE_ADMIN）
     * @param reviewStatus 审核状态过滤（3=待重审），可空
     * @param page         页码（从 1 起）
     * @param size         每页大小
     * @return 分页店铺列表
     */
    PageResult<ShopVO> listShops(String role, Integer reviewStatus, long page, long size);

    /**
     * 审核店铺信息变更（管理员，宽松重审）：通过则应用草稿新信息，拒绝则丢弃草稿（旧信息照常营业）；
     * 审核结果写入审计日志。
     *
     * @param role       当前用户角色（须为 ROLE_ADMIN）
     * @param operatorId 操作人用户ID（审计）
     * @param shopId     店铺 ID
     * @param dto        审核结果
     */
    void reviewShop(String role, Long operatorId, Long shopId, ReviewApplicationDTO dto);

    /**
     * 审核审计日志列表（管理员，分页）。
     *
     * @param role 当前用户角色（须为 ROLE_ADMIN）
     * @param page 页码（从 1 起）
     * @param size 每页大小
     * @return 分页审计日志
     */
    PageResult<AuditLog> auditList(String role, long page, long size);

    /**
     * 按用户 ID 查询店铺信息（内部接口，供商品服务 Feign 调用）。
     *
     * @param userId 用户 ID
     * @return 店铺信息；无店铺时返回 null
     */
    ShopInfo getShopByUserId(Long userId);

    /**
     * 按店铺 ID 查询店铺信息（内部接口，供商品服务展示店铺名等）。
     *
     * @param shopId 店铺 ID
     * @return 店铺信息；不存在时返回 null
     */
    ShopInfo getShopById(Long shopId);

    /**
     * 按店铺 ID 集合批量查询店铺信息（内部接口：会话列表等批量聚合场景，避免 N+1 逐个 Feign）。
     *
     * @param shopIds 店铺 ID 列表（允许重复/含不存在项，自动去重由调用方或 DB 处理）
     * @return 店铺信息列表（顺序与入参无关，调用方按 id 建 Map 使用）
     */
    List<ShopInfo> getShopsByIds(List<Long> shopIds);

    /**
     * 店铺主页（公开浏览）：店铺信息 + 店主公开信息（头像/昵称，不含手机号）+ 在售商品。
     *
     * @param shopId 店铺 ID
     * @return 店铺主页信息；店铺不存在或未营业时抛业务异常
     */
    ShopStorefrontVO getStorefront(Long shopId);

    /**
     * 查询店铺的履约能力（结算页用）：是否支持快递、自提门店地址与电话。
     *
     * @param shopId 店铺 ID
     * @return 履约能力视图；店铺未营业时返回 {@code null}（结算页据此提示不可下单）
     */
    ShopFulfillmentVO getFulfillment(Long shopId);

    /**
     * 营业中（审核已通过）店铺列表：首页公开浏览用。
     *
     * @return 店铺信息列表（仅 status=营业中）
     */
    List<ShopInfo> listEnabledShops();
}
