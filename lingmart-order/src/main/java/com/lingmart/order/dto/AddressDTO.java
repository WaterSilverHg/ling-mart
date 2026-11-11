package com.lingmart.order.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 收货地址新增/修改参数。
 *
 * <p>省市区与详细地址分开存、不在服务端拼接成一整串：卖家发货时需要分别填到快递单上，
 * 拼成一串再切回来既不可靠，也让前端无法做地区选择。</p>
 */
@Data
public class AddressDTO {

    /** 收货人姓名 */
    @NotBlank(message = "收货人姓名不能为空")
    @Size(max = 50, message = "收货人姓名不能超过 50 个字符")
    private String receiverName;

    /** 收货人手机号（中国大陆手机号） */
    @NotBlank(message = "收货人手机号不能为空")
    @Pattern(regexp = "^1[3-9]\\d{9}$", message = "手机号格式不正确")
    private String receiverPhone;

    /** 省 */
    @NotBlank(message = "省份不能为空")
    @Size(max = 50, message = "省份名称过长")
    private String province;

    /** 市 */
    @NotBlank(message = "城市不能为空")
    @Size(max = 50, message = "城市名称过长")
    private String city;

    /** 区/县 */
    @NotBlank(message = "区县不能为空")
    @Size(max = 50, message = "区县名称过长")
    private String district;

    /** 详细地址（街道、门牌号） */
    @NotBlank(message = "详细地址不能为空")
    @Size(max = 255, message = "详细地址不能超过 255 个字符")
    private String detailAddress;

    /** 是否设为默认地址；不传按「否」处理（首个地址会自动成为默认） */
    private Boolean isDefault;
}
