package com.lingmart.order.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lingmart.common.dto.SkuInfoDTO;
import com.lingmart.common.exception.BizException;
import com.lingmart.common.result.R;
import com.lingmart.common.result.ResultCode;
import com.lingmart.order.dto.CartItemDTO;
import com.lingmart.order.entity.Cart;
import com.lingmart.order.feign.ProductFeignClient;
import com.lingmart.order.mapper.CartMapper;
import com.lingmart.order.service.CartService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;

/**
 * 购物车实现：加购时经 Feign 取商品快照，价格以服务端为准。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CartServiceImpl implements CartService {

    private final CartMapper cartMapper;
    private final ProductFeignClient productFeignClient;

    @Override
    public void add(Long buyerId, CartItemDTO dto) {
        // 必须经 R.checkOk 校验下游业务码：product 对「未上架/未过审」等会返回 code!=200 且 data 为空，
        // 旧写法直接 .getData() 会在 data 为 null 时 NPE 成 500，把可读的业务提示吞掉
        SkuInfoDTO sku = R.checkOk(productFeignClient.getSkuInfo(dto.getSkuId()));
        if (sku == null) {
            throw new BizException("商品不存在");
        }

        Cart exist = cartMapper.selectOne(new LambdaQueryWrapper<Cart>()
                .eq(Cart::getBuyerId, buyerId)
                .eq(Cart::getSkuId, dto.getSkuId())
                .last("LIMIT 1"));
        if (exist != null) {
            Cart update = new Cart();
            update.setId(exist.getId());
            update.setNum(exist.getNum() + dto.getNum());
            cartMapper.updateById(update);
        } else {
            Cart cart = new Cart();
            cart.setBuyerId(buyerId);
            cart.setSpuId(sku.getSpuId());
            cart.setSkuId(sku.getSkuId());
            cart.setShopId(sku.getShopId());
            cart.setSpuTitle(sku.getSpuTitle());
            cart.setSkuTitle(sku.getSkuTitle());
            cart.setSkuImage(sku.getImage());
            cart.setPrice(sku.getPrice());
            cart.setNum(dto.getNum());
            cart.setChecked(1);
            try {
                cartMapper.insert(cart);
            } catch (DuplicateKeyException e) {
                // 并发/双击加购：uk_buyer_sku 唯一键兜底，另一请求已插入同一 (buyer_id, sku_id)。
                // 捕获后转为累加，避免把唯一键冲突冒成 500，也避免购物车里出现两条同款明细。
                Cart current = cartMapper.selectOne(new LambdaQueryWrapper<Cart>()
                        .eq(Cart::getBuyerId, buyerId)
                        .eq(Cart::getSkuId, dto.getSkuId())
                        .last("LIMIT 1"));
                if (current == null) {
                    throw new BizException("加入购物车失败，请重试");
                }
                Cart update = new Cart();
                update.setId(current.getId());
                update.setNum(current.getNum() + dto.getNum());
                cartMapper.updateById(update);
            }
        }
        log.info("加入购物车：buyerId={}, skuId={}, num={}", buyerId, dto.getSkuId(), dto.getNum());
    }

    @Override
    public void update(Long buyerId, Long cartId, Integer num, Integer checked) {
        Cart cart = requireOwned(buyerId, cartId);
        Cart update = new Cart();
        update.setId(cart.getId());
        if (num != null) {
            if (num < 1) {
                throw new BizException("数量至少为 1");
            }
            update.setNum(num);
        }
        if (checked != null) {
            update.setChecked(checked);
        }
        cartMapper.updateById(update);
    }

    @Override
    public void remove(Long buyerId, Long cartId) {
        Cart cart = requireOwned(buyerId, cartId);
        cartMapper.deleteById(cart.getId());
    }

    @Override
    public List<Cart> list(Long buyerId) {
        return cartMapper.selectList(new LambdaQueryWrapper<Cart>()
                .eq(Cart::getBuyerId, buyerId).orderByDesc(Cart::getCreateTime));
    }

    private Cart requireOwned(Long buyerId, Long cartId) {
        Cart cart = cartMapper.selectById(cartId);
        if (cart == null || !Objects.equals(cart.getBuyerId(), buyerId)) {
            throw new BizException(ResultCode.FORBIDDEN.getCode(), "无权操作该购物车条目");
        }
        return cart;
    }
}
