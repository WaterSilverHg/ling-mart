package com.lingmart.product.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 商品审核视图：管理员审查看板条目。
 *
 * <p>展示当前生效内容（正在售卖）与待审核草稿（draftContent，JSON），便于管理员判断。</p>
 */
@Data
public class ProductReviewVO {

    /** 商品SPU ID */
    private Long spuId;

    /** 所属店铺ID */
    private Long shopId;

    /** 店铺名称 */
    private String shopName;

    /** 当前生效标题（正在售卖的内容） */
    private String title;

    /** 当前生效主图 */
    private String mainImage;

    /** 当前最低价 */
    private BigDecimal price;

    /** 销售状态：0下架 1上架 */
    private Integer status;

    /** 审核状态：0待审核 1已通过 2已拒绝 3待重审 */
    private Integer reviewStatus;

    /** 待审核的新内容草稿(JSON)；待审核新商品为 null，待重审商品为新内容 */
    private String draftContent;

    /** 关联入驻申请ID */
    private Long applicationId;

    private LocalDateTime createTime;
}
