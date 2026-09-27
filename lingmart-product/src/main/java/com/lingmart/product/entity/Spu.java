package com.lingmart.product.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 商品 SPU 实体（标准商品单元）。
 *
 * <p>数据隔离：{@code shopId} 关联店铺，每个卖家只能操作自己店铺的商品。</p>
 */
@Data
@TableName("spu")
public class Spu {

    /** 主键（雪花，应用侧分配） */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 所属店铺 ID（数据隔离关键字段） */
    private Long shopId;

    /** 商品标题 */
    private String title;

    /** 副标题/卖点 */
    private String subtitle;

    /** 分类 ID */
    private Long categoryId;

    /** 品牌 ID */
    private Long brandId;

    /** 主图地址 */
    private String mainImage;

    /** 商品详情 */
    private String description;

    /** 销售状态：0 下架，1 上架 */
    private Integer status;

    /** 审核状态：0待审核 1已通过 2已拒绝 3待重审 */
    private Integer reviewStatus;

    /** 待审核的新内容草稿(JSON)：宽松重审，旧内容先卖、通过后才生效 */
    private String draftContent;

    /** 关联入驻申请ID（开店申请携带的初始商品） */
    private Long applicationId;

    /** 销量 */
    private Integer saleCount;

    /** 最低SKU价格（冗余字段，由 SKU 价最低值维护，列表/检索避免全量计算） */
    private BigDecimal minPrice;

    /** 创建时间 */
    private LocalDateTime createTime;

    /** 更新时间 */
    private LocalDateTime updateTime;
}
