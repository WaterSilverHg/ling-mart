package com.lingmart.order.controller;

import com.lingmart.common.constant.AuthConstants;
import com.lingmart.common.result.R;
import com.lingmart.order.dto.CartItemDTO;
import com.lingmart.order.entity.Cart;
import com.lingmart.order.service.CartService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 购物车接口（经网关，需登录）。
 */
@RestController
@RequestMapping("/api/cart")
@RequiredArgsConstructor
public class CartController {

    private final CartService cartService;

    /**
     * 购物车列表。
     */
    @GetMapping
    public R<List<Cart>> list(
            @RequestHeader(value = AuthConstants.HEADER_USER_ID, required = false) Long userId) {
        return R.ok(cartService.list(userId));
    }

    /**
     * 加入购物车。
     */
    @PostMapping
    public R<Void> add(@Valid @RequestBody CartItemDTO dto,
                       @RequestHeader(value = AuthConstants.HEADER_USER_ID, required = false) Long userId) {
        cartService.add(userId, dto);
        return R.ok();
    }

    /**
     * 更新购物车条目（数量/勾选）。
     */
    @PutMapping("/{id}")
    public R<Void> update(@PathVariable("id") Long id,
                          @RequestParam(required = false) Integer num,
                          @RequestParam(required = false) Integer checked,
                          @RequestHeader(value = AuthConstants.HEADER_USER_ID, required = false) Long userId) {
        cartService.update(userId, id, num, checked);
        return R.ok();
    }

    /**
     * 删除购物车条目。
     */
    @DeleteMapping("/{id}")
    public R<Void> remove(@PathVariable("id") Long id,
                          @RequestHeader(value = AuthConstants.HEADER_USER_ID, required = false) Long userId) {
        cartService.remove(userId, id);
        return R.ok();
    }
}
