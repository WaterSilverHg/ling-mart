package com.lingmart.order.service;

import com.lingmart.order.dto.AddressDTO;
import com.lingmart.order.entity.UserAddress;
import com.lingmart.order.vo.AddressVO;

import java.util.List;

/**
 * 收货地址簿服务。
 *
 * <p>所有方法都以 {@code userId} 为必填参数：地址是用户私有数据，
 * 归属校验必须在服务层完成，不能依赖「前端只传自己的 id」。</p>
 */
public interface AddressService {

    /**
     * 查询当前用户的地址列表（默认地址排在最前）。
     *
     * @param userId 当前用户ID
     * @return 地址视图列表，无地址时返回空列表
     */
    List<AddressVO> list(Long userId);

    /**
     * 新增收货地址。
     *
     * @param userId 当前用户ID
     * @param dto    地址参数
     * @return 新增后的地址视图
     */
    AddressVO create(Long userId, AddressDTO dto);

    /**
     * 修改收货地址（只能改自己的）。
     *
     * @param userId 当前用户ID
     * @param id     地址ID
     * @param dto    地址参数
     */
    void update(Long userId, Long id, AddressDTO dto);

    /**
     * 删除收货地址（只能删自己的）。若删除的是默认地址，自动把最近创建的地址提升为默认。
     *
     * @param userId 当前用户ID
     * @param id     地址ID
     */
    void delete(Long userId, Long id);

    /**
     * 把指定地址设为默认（同一用户同时只保留一条默认地址）。
     *
     * @param userId 当前用户ID
     * @param id     地址ID
     */
    void setDefault(Long userId, Long id);

    /**
     * 结算时解析最终使用的收货地址，供下单快照使用。
     *
     * <p>规则：传了 {@code addressId} 就用它（校验归属）；没传则用默认地址；
     * 连默认地址都没有时退回最近新增的一条；一条都没有则拒绝下单。</p>
     *
     * @param userId    买家用户ID
     * @param addressId 买家选择的地址ID，可为 null
     * @return 地址实体（调用方负责快照，不要长期持有）
     */
    UserAddress resolveForCheckout(Long userId, Long addressId);
}
