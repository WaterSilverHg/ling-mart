package com.lingmart.order.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.lingmart.common.exception.BizException;
import com.lingmart.common.result.ResultCode;
import com.lingmart.order.dto.AddressDTO;
import com.lingmart.order.entity.UserAddress;
import com.lingmart.order.mapper.UserAddressMapper;
import com.lingmart.order.service.AddressService;
import com.lingmart.order.vo.AddressVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 收货地址簿实现。
 *
 * <p><b>默认地址为什么靠事务而不是唯一索引</b>：MySQL 没有部分索引，而 {@code is_default}
 * 在绝大多数行上都是 0——若建 {@code UNIQUE(user_id, is_default)}，同一用户第二条普通地址
 * 就会冲突。所以默认地址的唯一性由「先清零、再置一」在同一事务内保证。
 * 极端并发（同一用户同时点两次「设为默认」）下理论上仍可能出现两条默认地址，
 * 但读取侧用 {@code LIMIT 1} 容错，不会导致下单失败。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AddressServiceImpl implements AddressService {

    /** 单用户地址数量上限：防脚本刷表，也符合真实使用习惯 */
    private static final int MAX_ADDRESSES = 20;

    private final UserAddressMapper userAddressMapper;

    @Override
    public List<AddressVO> list(Long userId) {
        requireLogin(userId);
        return userAddressMapper.selectList(new LambdaQueryWrapper<UserAddress>()
                        .eq(UserAddress::getUserId, userId)
                        // 默认地址排最前，其次按创建时间倒序（最近添加的靠前）
                        .orderByDesc(UserAddress::getIsDefault)
                        .orderByDesc(UserAddress::getCreateTime))
                .stream()
                .map(AddressServiceImpl::toVO)
                .toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AddressVO create(Long userId, AddressDTO dto) {
        requireLogin(userId);
        Long count = userAddressMapper.selectCount(new LambdaQueryWrapper<UserAddress>()
                .eq(UserAddress::getUserId, userId));
        if (count != null && count >= MAX_ADDRESSES) {
            throw new BizException("最多保存 " + MAX_ADDRESSES + " 个收货地址，请先删除不用的");
        }
        // 首个地址自动成为默认：否则用户填完地址去结算仍会因为「没有默认地址」而困惑
        boolean makeDefault = Boolean.TRUE.equals(dto.getIsDefault()) || count == null || count == 0;
        if (makeDefault) {
            clearDefault(userId);
        }
        UserAddress entity = new UserAddress();
        copy(dto, entity);
        entity.setUserId(userId);
        entity.setIsDefault(makeDefault ? 1 : 0);
        userAddressMapper.insert(entity);
        log.info("新增收货地址：userId={}, addressId={}, isDefault={}", userId, entity.getId(), makeDefault);
        return toVO(entity);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void update(Long userId, Long id, AddressDTO dto) {
        requireLogin(userId);
        requireOwned(userId, id);
        // 只有显式要求「设为默认」才动默认标记；传 false/null 表示保持现状，
        // 避免「编辑地址时没勾默认」把默认地址意外取消掉
        boolean makeDefault = Boolean.TRUE.equals(dto.getIsDefault());
        if (makeDefault) {
            clearDefault(userId);
        }
        UserAddress update = new UserAddress();
        update.setId(id);
        copy(dto, update);
        if (makeDefault) {
            update.setIsDefault(1);
        }
        userAddressMapper.updateById(update);
        log.info("修改收货地址：userId={}, addressId={}", userId, id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long userId, Long id) {
        requireLogin(userId);
        UserAddress exist = requireOwned(userId, id);
        userAddressMapper.deleteById(id);
        // 删掉的是默认地址 → 把最近新增的一条顶上来，避免用户下次结算时"没有默认地址"
        if (Integer.valueOf(1).equals(exist.getIsDefault())) {
            UserAddress latest = userAddressMapper.selectOne(new LambdaQueryWrapper<UserAddress>()
                    .eq(UserAddress::getUserId, userId)
                    .orderByDesc(UserAddress::getCreateTime)
                    .last("LIMIT 1"));
            if (latest != null) {
                UserAddress update = new UserAddress();
                update.setId(latest.getId());
                update.setIsDefault(1);
                userAddressMapper.updateById(update);
                log.info("默认地址被删除，已提升最近地址为默认：userId={}, addressId={}", userId, latest.getId());
            }
        }
        log.info("删除收货地址：userId={}, addressId={}", userId, id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void setDefault(Long userId, Long id) {
        requireLogin(userId);
        requireOwned(userId, id);
        clearDefault(userId);
        UserAddress update = new UserAddress();
        update.setId(id);
        update.setIsDefault(1);
        userAddressMapper.updateById(update);
        log.info("设置默认地址：userId={}, addressId={}", userId, id);
    }

    @Override
    public UserAddress resolveForCheckout(Long userId, Long addressId) {
        requireLogin(userId);
        if (addressId != null) {
            return requireOwned(userId, addressId);
        }
        UserAddress def = userAddressMapper.selectOne(new LambdaQueryWrapper<UserAddress>()
                .eq(UserAddress::getUserId, userId)
                .eq(UserAddress::getIsDefault, 1)
                .last("LIMIT 1"));
        if (def != null) {
            return def;
        }
        UserAddress latest = userAddressMapper.selectOne(new LambdaQueryWrapper<UserAddress>()
                .eq(UserAddress::getUserId, userId)
                .orderByDesc(UserAddress::getCreateTime)
                .last("LIMIT 1"));
        if (latest == null) {
            throw new BizException("请先添加收货地址");
        }
        return latest;
    }

    /** 把该用户现有的默认标记清零。 */
    private void clearDefault(Long userId) {
        userAddressMapper.update(null, new LambdaUpdateWrapper<UserAddress>()
                .eq(UserAddress::getUserId, userId)
                .eq(UserAddress::getIsDefault, 1)
                .set(UserAddress::getIsDefault, 0));
    }

    /** 校验地址存在且属于该用户。不存在与他人的地址统一报「不存在」，避免枚举探测。 */
    private UserAddress requireOwned(Long userId, Long id) {
        if (id == null) {
            throw new BizException("收货地址不能为空");
        }
        UserAddress address = userAddressMapper.selectOne(new LambdaQueryWrapper<UserAddress>()
                .eq(UserAddress::getId, id)
                .eq(UserAddress::getUserId, userId));
        if (address == null) {
            throw new BizException("收货地址不存在");
        }
        return address;
    }

    private static void requireLogin(Long userId) {
        if (userId == null) {
            throw new BizException(ResultCode.UNAUTHORIZED.getCode(), "未登录或登录已过期");
        }
    }

    private static void copy(AddressDTO dto, UserAddress target) {
        target.setReceiverName(dto.getReceiverName());
        target.setReceiverPhone(dto.getReceiverPhone());
        target.setProvince(dto.getProvince());
        target.setCity(dto.getCity());
        target.setDistrict(dto.getDistrict());
        target.setDetailAddress(dto.getDetailAddress());
    }

    private static AddressVO toVO(UserAddress entity) {
        AddressVO vo = new AddressVO();
        vo.setId(entity.getId());
        vo.setReceiverName(entity.getReceiverName());
        vo.setReceiverPhone(entity.getReceiverPhone());
        vo.setProvince(entity.getProvince());
        vo.setCity(entity.getCity());
        vo.setDistrict(entity.getDistrict());
        vo.setDetailAddress(entity.getDetailAddress());
        vo.setIsDefault(Integer.valueOf(1).equals(entity.getIsDefault()));
        vo.setFullAddress(joinFullAddress(entity));
        return vo;
    }

    /**
     * 拼接展示用完整地址（省 市 区 详细地址）。
     *
     * @param entity 地址实体
     * @return 用空格分隔的完整地址
     */
    public static String joinFullAddress(UserAddress entity) {
        return String.join(" ",
                nullToEmpty(entity.getProvince()),
                nullToEmpty(entity.getCity()),
                nullToEmpty(entity.getDistrict()),
                nullToEmpty(entity.getDetailAddress())).trim();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
