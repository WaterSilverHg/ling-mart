package com.lingmart.order.service.impl;

import com.lingmart.common.exception.BizException;
import com.lingmart.order.dto.AddressDTO;
import com.lingmart.order.entity.UserAddress;
import com.lingmart.order.mapper.UserAddressMapper;
import com.lingmart.order.support.MybatisLambdaCache;
import com.lingmart.order.vo.AddressVO;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 收货地址簿单元测试（Mockito，不依赖数据库）。
 *
 * <p>覆盖默认地址规则（首个自动默认、切换时先清零）、数量上限、归属校验
 * 与「删掉默认地址后自动提升」这四条最容易出错的规则。</p>
 */
@ExtendWith(MockitoExtension.class)
class AddressServiceImplTest {

    @Mock
    private UserAddressMapper userAddressMapper;

    @InjectMocks
    private AddressServiceImpl service;

    /**
     * 手工初始化 MyBatis-Plus 的 Lambda 缓存（{@code SFunction} → 列名）。
     *
     * <p>本测试是纯 Mockito、没有 Spring/MyBatis 上下文，不初始化的话
     * {@code LambdaUpdateWrapper#set} 在构造 SQL 片段时会抛
     * {@code can not find lambda cache for this entity}。具体逻辑见 {@link MybatisLambdaCache}。</p>
     */
    @BeforeAll
    static void initMybatisPlusLambdaCache() {
        MybatisLambdaCache.initOrderModule();
    }

    // ==================== 新增 ====================

    @Test
    void create_firstAddress_shouldBecomeDefault() {
        when(userAddressMapper.selectCount(any())).thenReturn(0L);
        when(userAddressMapper.insert(any(UserAddress.class))).thenReturn(1);

        AddressVO vo = service.create(1L, dto(null));

        assertTrue(vo.getIsDefault(), "首个地址应自动成为默认");
        // 成为默认前必须先清掉旧的默认标记（此处虽然没旧默认，也要走同一路径）
        verify(userAddressMapper).update(isNull(), any());
    }

    @Test
    void create_notDefaultWhenExplicitlyFalse() {
        when(userAddressMapper.selectCount(any())).thenReturn(3L);
        when(userAddressMapper.insert(any(UserAddress.class))).thenReturn(1);

        AddressVO vo = service.create(1L, dto(false));

        assertFalse(vo.getIsDefault());
        verify(userAddressMapper, never()).update(isNull(), any());
    }

    @Test
    void create_shouldRejectWhenReachingLimit() {
        when(userAddressMapper.selectCount(any())).thenReturn(20L);

        assertThrows(BizException.class, () -> service.create(1L, dto(null)));
        verify(userAddressMapper, never()).insert(any(UserAddress.class));
    }

    // ==================== 修改 / 删除 / 默认 ====================

    @Test
    void update_shouldRejectOthersAddress() {
        // 查不到（或不属于当前用户）→ 统一「不存在」，防枚举
        when(userAddressMapper.selectOne(any())).thenReturn(null);

        assertThrows(BizException.class, () -> service.update(1L, 99L, dto(null)));
        verify(userAddressMapper, never()).updateById(any(UserAddress.class));
    }

    @Test
    void delete_defaultAddress_shouldPromoteLatestRemaining() {
        UserAddress deleting = address(1L, 1L, 1);
        UserAddress remaining = address(2L, 1L, 0);
        when(userAddressMapper.selectOne(any())).thenReturn(deleting, remaining);
        when(userAddressMapper.deleteById(1L)).thenReturn(1);
        when(userAddressMapper.updateById(any(UserAddress.class))).thenReturn(1);

        service.delete(1L, 1L);

        // 默认被删后，其余地址里最近的一条应被提升为默认
        verify(userAddressMapper).updateById(org.mockito.ArgumentMatchers.argThat(
                (UserAddress u) -> Long.valueOf(2L).equals(u.getId()) && Integer.valueOf(1).equals(u.getIsDefault())));
    }

    @Test
    void setDefault_shouldClearThenSet() {
        when(userAddressMapper.selectOne(any())).thenReturn(address(5L, 1L, 0));
        when(userAddressMapper.updateById(any(UserAddress.class))).thenReturn(1);

        service.setDefault(1L, 5L);

        // 先整行清零，再把目标置一
        verify(userAddressMapper).update(isNull(), any());
        verify(userAddressMapper).updateById(org.mockito.ArgumentMatchers.argThat(
                (UserAddress u) -> Long.valueOf(5L).equals(u.getId()) && Integer.valueOf(1).equals(u.getIsDefault())));
    }

    // ==================== 结算解析 ====================

    @Test
    void resolve_shouldRejectWhenNoAddress() {
        when(userAddressMapper.selectOne(any())).thenReturn(null);

        BizException e = assertThrows(BizException.class, () -> service.resolveForCheckout(1L, null));
        assertTrue(e.getMessage().contains("请先添加收货地址"));
    }

    @Test
    void resolve_shouldUseRequestedAddress() {
        UserAddress chosen = address(8L, 1L, 0);
        when(userAddressMapper.selectOne(any())).thenReturn(chosen);

        UserAddress result = service.resolveForCheckout(1L, 8L);

        assertNotNull(result);
        assertEquals(8L, result.getId());
    }

    @Test
    void resolve_withoutId_shouldFallBackToDefault() {
        UserAddress def = address(3L, 1L, 1);
        when(userAddressMapper.selectOne(any())).thenReturn(def);

        UserAddress result = service.resolveForCheckout(1L, null);

        assertEquals(3L, result.getId());
    }

    // ==================== 辅助 ====================

    private AddressDTO dto(Boolean isDefault) {
        AddressDTO dto = new AddressDTO();
        dto.setReceiverName("张三");
        dto.setReceiverPhone("13900000001");
        dto.setProvince("浙江省");
        dto.setCity("杭州市");
        dto.setDistrict("西湖区");
        dto.setDetailAddress("文一西路 1 号");
        dto.setIsDefault(isDefault);
        return dto;
    }

    private UserAddress address(Long id, Long userId, int isDefault) {
        UserAddress address = new UserAddress();
        address.setId(id);
        address.setUserId(userId);
        address.setReceiverName("张三");
        address.setReceiverPhone("13900000001");
        address.setProvince("浙江省");
        address.setCity("杭州市");
        address.setDistrict("西湖区");
        address.setDetailAddress("文一西路 1 号");
        address.setIsDefault(isDefault);
        return address;
    }
}
