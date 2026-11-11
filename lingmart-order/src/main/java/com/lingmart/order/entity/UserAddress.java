package com.lingmart.order.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 收货地址簿实体。
 *
 * <p>地址簿是「下单时可选的模板」，订单生成时会把选中的地址**快照**到 {@code order} 表的
 * {@code receiver_*} 列。两者不是外键关系：买家事后修改或删除地址，已生成的订单不受影响——
 * 这是电商订单的基本要求（发货凭证必须可追溯，不能随地址簿变动）。</p>
 */
@Data
@TableName("user_address")
public class UserAddress {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 归属用户ID */
    private Long userId;

    /** 收货人姓名 */
    private String receiverName;

    /** 收货人手机号 */
    private String receiverPhone;

    /** 省 */
    private String province;

    /** 市 */
    private String city;

    /** 区/县 */
    private String district;

    /** 详细地址（街道门牌） */
    private String detailAddress;

    /** 是否默认地址：1 是 / 0 否 */
    private Integer isDefault;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
