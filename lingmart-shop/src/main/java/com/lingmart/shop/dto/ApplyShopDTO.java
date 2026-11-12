package com.lingmart.shop.dto;

import com.lingmart.common.dto.SpuDTO;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 申请入驻 / 更新店铺请求参数。
 *
 * <p>开店申请必须携带初始商品清单（要卖的东西，服务层校验非空）；审核通过后商品才可销售。
 * 更新店铺时忽略 {@code products}。</p>
 */
@Data
public class ApplyShopDTO {

    /** 店铺名称 */
    @NotBlank(message = "店铺名称不能为空")
    @Size(max = 50, message = "店铺名称最长 50 个字符")
    private String shopName;

    /** 店铺 logo 地址 */
    @Size(max = 255, message = "logo 地址过长")
    private String shopLogo;

    /** 店铺描述 */
    @Size(max = 255, message = "店铺描述最长 255 个字符")
    private String description;

    /**
     * 门店地址（买家自提点）。
     *
     * <p>必填：自提是本项目的默认履约方式，没有地址买家就不知道该去哪儿取货。
     * 存量店铺（本次升级前创建）该字段为 NULL，补填之前无法接收自提订单。</p>
     */
    @NotBlank(message = "门店地址不能为空")
    @Size(max = 255, message = "门店地址最长 255 个字符")
    private String address;

    /** 门店联系电话（自提/售后联系用） */
    @NotBlank(message = "门店联系电话不能为空")
    @Pattern(regexp = "^1[3-9]\\d{9}$|^0\\d{2,3}-?\\d{7,8}$",
            message = "联系电话格式不正确（手机号或带区号的固话）")
    private String contactPhone;

    /**
     * 是否支持快递发货。
     *
     * <p>不传或 false 表示「仅到店自提」——这是自营实体店的常见配置。
     * 开启后买家在结算页可以额外选择快递，并需要提供收货地址。</p>
     */
    private Boolean supportExpress;

    /** 开店要卖的商品清单（申请必填；更新店铺时忽略） */
    @Valid
    private List<SpuDTO> products;
}
