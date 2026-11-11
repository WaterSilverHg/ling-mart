package com.lingmart.order.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 下单请求：单店铺订单（前端按店铺分组结算）。
 */
@Data
public class OrderCreateDTO {

    /**
     * 订单商品条目（同属一个店铺）。
     *
     * <p>上限 50 条：建单时每条 SKU 都要走一次 product 的 Feign 取快照，且在 Seata 全局事务内。
     * 不设上限时，一个请求带上万条即可制造上万次远程调用并长期占住全局锁——
     * 这是「一次请求换一次服务级阻塞」的放大器，而正常下单不可能超过 50 个 SKU。</p>
     */
    @NotEmpty(message = "订单商品不能为空")
    @Size(max = 50, message = "单次下单最多 50 个商品")
    private List<OrderItemDTO> items;

    /** 买家备注 */
    private String buyerRemark;

    /**
     * 收货地址 ID（来自地址簿）。
     *
     * <p>不传时服务端按「默认地址 → 最近新增地址」顺序回退；一条地址都没有则拒绝下单。
     * 之所以允许不传：老客户端与压测脚本仍按原协议下单，用默认地址即可，
     * 不必为了这个字段强制所有人升级。</p>
     */
    private Long addressId;
}
