package com.lingmart.order.vo;

import lombok.Data;

/**
 * 收货地址视图。
 */
@Data
public class AddressVO {

    private Long id;

    private String receiverName;

    private String receiverPhone;

    private String province;

    private String city;

    private String district;

    private String detailAddress;

    /** 是否默认地址 */
    private Boolean isDefault;

    /** 展示用完整地址：省 市 区 详细地址（便于前端直接渲染，无需自己拼接） */
    private String fullAddress;
}
