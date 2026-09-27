package com.lingmart.auth.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lingmart.auth.entity.User;

/**
 * 用户表 Mapper。
 *
 * <p>继承 MyBatis-Plus {@link BaseMapper}，默认提供单表 CRUD；复杂查询可在
 * {@code src/main/resources/mapper/UserMapper.xml} 中扩展。</p>
 */
public interface UserMapper extends BaseMapper<User> {
}
