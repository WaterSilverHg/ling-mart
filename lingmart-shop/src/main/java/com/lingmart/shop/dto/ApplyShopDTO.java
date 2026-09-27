package com.lingmart.shop.dto;

import com.lingmart.common.dto.SpuDTO;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
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

    /** 开店要卖的商品清单（申请必填；更新店铺时忽略） */
    @Valid
    private List<SpuDTO> products;
}
