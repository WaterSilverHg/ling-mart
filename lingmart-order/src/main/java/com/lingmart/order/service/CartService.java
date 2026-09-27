package com.lingmart.order.service;

import com.lingmart.order.dto.CartItemDTO;
import com.lingmart.order.entity.Cart;

import java.util.List;

/**
 * 购物车业务接口。
 */
public interface CartService {

    /**
     * 加入购物车：同 SKU 已存在则累加数量。
     *
     * @param buyerId 买家用户ID
     * @param dto     加购参数
     */
    void add(Long buyerId, CartItemDTO dto);

    /**
     * 更新购物车条目（数量/勾选）。
     *
     * @param buyerId 买家用户ID
     * @param cartId  购物车条目ID
     * @param num     数量（可为 null 表示不修改）
     * @param checked 是否勾选（可为 null 表示不修改）
     */
    void update(Long buyerId, Long cartId, Integer num, Integer checked);

    /**
     * 删除购物车条目。
     *
     * @param buyerId 买家用户ID
     * @param cartId  购物车条目ID
     */
    void remove(Long buyerId, Long cartId);

    /**
     * 买家购物车列表。
     *
     * @param buyerId 买家用户ID
     * @return 购物车条目
     */
    List<Cart> list(Long buyerId);
}
