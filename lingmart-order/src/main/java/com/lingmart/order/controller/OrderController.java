package com.lingmart.order.controller;

import com.alibaba.excel.EasyExcel;
import com.alibaba.excel.ExcelWriter;
import com.alibaba.excel.write.metadata.WriteSheet;
import com.lingmart.common.constant.AuthConstants;
import com.lingmart.common.result.PageResult;
import com.lingmart.common.result.R;
import com.lingmart.order.dto.OrderCreateDTO;
import com.lingmart.order.dto.OrderExportRow;
import com.lingmart.order.dto.RefundRequestDTO;
import com.lingmart.order.dto.RefundReviewDTO;
import com.lingmart.order.dto.ShipDTO;
import com.lingmart.order.service.OrderService;
import com.lingmart.order.vo.OrderDetailVO;
import com.lingmart.order.vo.SellerStatsVO;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 订单接口（经网关，需登录）。
 *
 * <p>用户身份由网关透传的 {@code X-User-Id} 请求头提供。</p>
 */
@RestController
@RequestMapping("/api/order")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;

    /**
     * 下单（Seata 全局事务：建单 + 锁库存；支持 Idempotency-Key 幂等防重复下单）。
     */
    @PostMapping
    public R<String> create(@Valid @RequestBody OrderCreateDTO dto,
                            @RequestHeader(value = AuthConstants.HEADER_USER_ID, required = false) Long userId,
                            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        String orderSn = orderService.createOrder(userId, idempotencyKey, dto);
        // 全局事务提交后投递支付超时消息
        orderService.sendTimeoutMessage(orderSn);
        return R.ok(orderSn);
    }

    /**
     * 模拟支付。
     */
    @PostMapping("/{orderSn}/pay")
    public R<Void> pay(@PathVariable("orderSn") String orderSn,
                       @RequestHeader(value = AuthConstants.HEADER_USER_ID, required = false) Long userId) {
        orderService.pay(userId, orderSn);
        return R.ok();
    }

    /**
     * 取消订单。
     */
    @PostMapping("/{orderSn}/cancel")
    public R<Void> cancel(@PathVariable("orderSn") String orderSn,
                          @RequestHeader(value = AuthConstants.HEADER_USER_ID, required = false) Long userId) {
        orderService.cancel(userId, orderSn);
        return R.ok();
    }

    /**
     * 卖家发货（v0.27 起需指定发货方式；选快递时另需快递公司与运单号）。
     */
    @PostMapping("/{orderSn}/ship")
    public R<Void> ship(@PathVariable("orderSn") String orderSn,
                        @Valid @RequestBody ShipDTO dto,
                        @RequestHeader(value = AuthConstants.HEADER_USER_ID, required = false) Long userId) {
        orderService.ship(userId, orderSn, dto);
        return R.ok();
    }

    /**
     * 买家确认收货。
     */
    @PostMapping("/{orderSn}/confirm")
    public R<Void> confirm(@PathVariable("orderSn") String orderSn,
                           @RequestHeader(value = AuthConstants.HEADER_USER_ID, required = false) Long userId) {
        orderService.confirm(userId, orderSn);
        return R.ok();
    }

    /**
     * 买家申请退款（已支付/已发货/已完成订单、且无退款中/已退款记录时可申请）。
     */
    @PostMapping("/{orderSn}/refund")
    public R<Void> refund(@PathVariable("orderSn") String orderSn,
                          @Valid @RequestBody RefundRequestDTO dto,
                          @RequestHeader(value = AuthConstants.HEADER_USER_ID, required = false) Long userId) {
        orderService.requestRefund(userId, orderSn, dto.getReason());
        return R.ok();
    }

    /**
     * 卖家审核退款：通过则模拟退款（订单置已退款 + 库存回补），拒绝则置退款拒绝。
     */
    @PostMapping("/{orderSn}/refund/review")
    public R<Void> reviewRefund(@PathVariable("orderSn") String orderSn,
                                @Valid @RequestBody RefundReviewDTO dto,
                                @RequestHeader(value = AuthConstants.HEADER_USER_ID, required = false) Long userId) {
        orderService.reviewRefund(userId, orderSn, Boolean.TRUE.equals(dto.getApprove()), dto.getRejectReason());
        return R.ok();
    }

    /**
     * 买家订单列表（分页）。
     */
    @GetMapping("/list")
    public R<PageResult<OrderDetailVO>> list(
            @RequestParam(required = false, defaultValue = "1") long page,
            @RequestParam(required = false, defaultValue = "10") long size,
            @RequestParam(required = false) Integer orderStatus,
            @RequestHeader(value = AuthConstants.HEADER_USER_ID, required = false) Long userId) {
        return R.ok(orderService.buyerOrders(userId, page, size, orderStatus));
    }

    /**
     * 卖家订单列表（按当前用户店铺过滤，分页）。
     */
    @GetMapping("/seller/list")
    public R<PageResult<OrderDetailVO>> sellerList(
            @RequestParam(required = false, defaultValue = "1") long page,
            @RequestParam(required = false, defaultValue = "10") long size,
            @RequestHeader(value = AuthConstants.HEADER_USER_ID, required = false) Long userId) {
        return R.ok(orderService.sellerOrders(userId, page, size));
    }

    /**
     * 订单详情（买家或对应店铺卖家可见）。
     */
    @GetMapping("/{orderSn}")
    public R<OrderDetailVO> detail(@PathVariable("orderSn") String orderSn,
                                   @RequestHeader(value = AuthConstants.HEADER_USER_ID, required = false) Long userId) {
        return R.ok(orderService.detail(userId, orderSn));
    }

    /**
     * 卖家经营数据统计（概览 / 状态分布 / 销售趋势 / 热销 TOP5）。
     *
     * @param range  统计范围：7d / 30d / all
     * @param userId 卖家用户ID
     * @return 统计视图
     */
    @GetMapping("/seller/stats")
    public R<SellerStatsVO> sellerStats(@RequestParam(required = false, defaultValue = "7d") String range,
                                        @RequestHeader(value = AuthConstants.HEADER_USER_ID, required = false) Long userId) {
        return R.ok(orderService.sellerStats(userId, range));
    }

    /**
     * 卖家订单导出（Excel，一行一个订单明细，游标流式写出）。
     *
     * @param days  导出近 N 天（&le; 0 表示全部）
     * @param userId 卖家用户ID
     * @param response 响应（写 xlsx 流）
     */
    @GetMapping("/seller/export")
    public void sellerExport(@RequestParam(required = false, defaultValue = "30") int days,
                             @RequestHeader(value = AuthConstants.HEADER_USER_ID, required = false) Long userId,
                             HttpServletResponse response) throws IOException {
        streamExcel(response, "订单导出",
                writeRow -> orderService.streamSellerOrders(userId, days, writeRow));
    }

    /**
     * 买家订单导出（Excel，一行一个订单明细，游标流式写出）。
     *
     * @param days   导出近 N 天（&le; 0 表示全部）
     * @param userId 买家用户ID
     * @param response 响应（写 xlsx 流）
     */
    @GetMapping("/export")
    public void buyerExport(@RequestParam(required = false, defaultValue = "30") int days,
                            @RequestHeader(value = AuthConstants.HEADER_USER_ID, required = false) Long userId,
                            HttpServletResponse response) throws IOException {
        streamExcel(response, "我的订单",
                writeRow -> orderService.streamBuyerOrders(userId, days, writeRow));
    }

    /**
     * 以附件方式流式写出 xlsx（文件名 UTF-8 编码，中文浏览器下载正常）。
     *
     * <p>DB 游标逐行 → 攒 500 行 flush 一次写 Excel，大订单量下内存占用恒定，
     * 不再把全量导出行加载进内存。</p>
     *
     * @param response 响应
     * @param sheetName 工作表名
     * @param streamer  流式生产回调（传入行写出器后逐行写出）
     */
    private void streamExcel(HttpServletResponse response, String sheetName,
                             Consumer<Consumer<OrderExportRow>> streamer) throws IOException {
        String fileName = URLEncoder.encode(sheetName + "_" + LocalDate.now(), StandardCharsets.UTF_8).replace("+", "%20");
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setCharacterEncoding("utf-8");
        response.setHeader("Content-Disposition", "attachment;filename*=utf-8''" + fileName + ".xlsx");

        ExcelWriter excelWriter = EasyExcel.write(response.getOutputStream(), OrderExportRow.class).build();
        WriteSheet sheet = EasyExcel.writerSheet(sheetName).build();
        try {
            // 分批写出：攒 500 行 flush，避免逐行写（性能差）也避免全量加载（内存）
            List<OrderExportRow> buffer = new ArrayList<>(500);
            Consumer<OrderExportRow> writeRow = row -> {
                buffer.add(row);
                if (buffer.size() >= 500) {
                    excelWriter.write(new ArrayList<>(buffer), sheet);
                    buffer.clear();
                }
            };
            streamer.accept(writeRow);
            if (!buffer.isEmpty()) {
                excelWriter.write(buffer, sheet);
            }
        } finally {
            excelWriter.finish();
        }
    }
}
