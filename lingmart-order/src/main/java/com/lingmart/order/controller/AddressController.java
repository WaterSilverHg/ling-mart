package com.lingmart.order.controller;

import com.lingmart.common.constant.AuthConstants;
import com.lingmart.common.result.R;
import com.lingmart.order.dto.AddressDTO;
import com.lingmart.order.service.AddressService;
import com.lingmart.order.vo.AddressVO;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 收货地址簿接口（经网关，需登录）。
 *
 * <p>路径放在 {@code /api/order/addresses} 而不是单独的 {@code /api/address}：
 * 地址簿是买家侧的结算数据，与购物车同属订单域；放在 order 服务里可以避免下单链路
 * 再增加一次跨服务调用（下单已经在 Seata 全局事务内，每一次 RPC 都会拉长全局锁持有时间）。</p>
 *
 * <p>注意与 {@code GET /api/order/{orderSn}} 的路径重叠：Spring 的路径匹配会优先选择
 * 更具体的字面量路径 {@code /addresses}，不会把 "addresses" 当成订单号。</p>
 */
@RestController
@RequestMapping("/api/order/addresses")
@RequiredArgsConstructor
public class AddressController {

    private final AddressService addressService;

    /** 地址列表（默认地址排最前）。 */
    @GetMapping
    public R<List<AddressVO>> list(
            @RequestHeader(value = AuthConstants.HEADER_USER_ID, required = false) Long userId) {
        return R.ok(addressService.list(userId));
    }

    /** 新增地址；首个地址自动成为默认。 */
    @PostMapping
    public R<AddressVO> create(@Valid @RequestBody AddressDTO dto,
                               @RequestHeader(value = AuthConstants.HEADER_USER_ID, required = false) Long userId) {
        return R.ok(addressService.create(userId, dto));
    }

    /** 修改地址（只能改自己的）。 */
    @PutMapping("/{id}")
    public R<Void> update(@PathVariable("id") Long id,
                          @Valid @RequestBody AddressDTO dto,
                          @RequestHeader(value = AuthConstants.HEADER_USER_ID, required = false) Long userId) {
        addressService.update(userId, id, dto);
        return R.ok();
    }

    /** 删除地址；删掉默认地址时会自动提升最近新增的一条。 */
    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable("id") Long id,
                          @RequestHeader(value = AuthConstants.HEADER_USER_ID, required = false) Long userId) {
        addressService.delete(userId, id);
        return R.ok();
    }

    /** 设为默认地址。 */
    @PutMapping("/{id}/default")
    public R<Void> setDefault(@PathVariable("id") Long id,
                              @RequestHeader(value = AuthConstants.HEADER_USER_ID, required = false) Long userId) {
        addressService.setDefault(userId, id);
        return R.ok();
    }
}
