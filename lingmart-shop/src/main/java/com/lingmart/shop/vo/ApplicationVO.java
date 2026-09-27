package com.lingmart.shop.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 入驻申请视图对象。
 */
@Data
public class ApplicationVO {

    /** 申请 ID */
    private Long id;

    /** 申请店铺名称 */
    private String shopName;

    /** 店铺描述 */
    private String description;

    /** 状态：0 待审核，1 已通过，2 已拒绝 */
    private Integer status;

    /** 拒绝原因 */
    private String rejectReason;

    /** 申请时间 */
    private LocalDateTime createTime;
}
