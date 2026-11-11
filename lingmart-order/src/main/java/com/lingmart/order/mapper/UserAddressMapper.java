package com.lingmart.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lingmart.order.entity.UserAddress;
import org.apache.ibatis.annotations.Mapper;

/**
 * 收货地址簿 Mapper。
 */
@Mapper
public interface UserAddressMapper extends BaseMapper<UserAddress> {
}
