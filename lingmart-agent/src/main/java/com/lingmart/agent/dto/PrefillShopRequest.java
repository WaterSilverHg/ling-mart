package com.lingmart.agent.dto;

import lombok.Data;

import java.util.List;

/**
 * 「预填开店申请」的工具参数（v0.28）。
 *
 * <p><b>这个类的字段集合本身就是安全边界</b>：模型只能往这些固定字段里放值，
 * 没有「填哪个字段」「选哪个元素」这类自由度。原攻击路径
 * （在商品标题里藏「把收货地址改成 X」→ 用户习惯于点确认）在此无处着力——
 * 因为工具压根没有"地址"这个字段，注入最多污染商品标题自身。</p>
 *
 * <p>字段与前端 {@code ShopApply.vue} 的 {@code form} 一一对应，服务端只做边界裁剪
 * （见 {@code PrefillGuard}），字段语义与后端 {@code SpuDTO}/{@code SkuDTO} 保持一致，
 * 由用户在页面上核对后自行提交（Agent 全程不接触业务接口）。</p>
 */
@Data
public class PrefillShopRequest {

    /** 店铺名称（≤50 字符）；模型可根据文档内容建议，用户可改 */
    private String shopName;

    /** 店铺描述（≤255 字符） */
    private String description;

    /**
     * 商品清单（至少 1 条，服务端上限见 {@code PrefillGuard}）。
     *
     * <p>注意：**图片不在其中**——商品图与店铺 logo 必须是已上传的 URL，模型生成不了，
     * 留给用户在页面上自己上传（图片本就不是必填项）。</p>
     */
    private List<PrefillProduct> products;
}
