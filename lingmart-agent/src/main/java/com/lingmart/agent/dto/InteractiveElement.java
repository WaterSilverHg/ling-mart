package com.lingmart.agent.dto;

/**
 * 页面上的一个可交互元素（按钮/链接/输入框等）。
 *
 * @param tag       标签名（如 button / a / input）
 * @param text      元素的可见文本（输入框取占位符，可能为空）
 * @param elementId 前端分配的稳定元素 ID（形如 {@code c12}），供 click_element 使用。
 *
 *                  <p><b>为什么不用 CSS 选择器</b>：选择器是"从 DOM 结构反推位置"，
 *                  页面一旦重排（v-for 重建、列表刷新）就会指向错误的行——那正是「误点错行」的成因。
 *                  而 ID 是采集时打在元素自身上的属性，跟着元素走，重排也不会指错。
 *                  顺带它还短得多：{@code c12} 对比 {@code div > ul:nth-of-type(2) > li:nth-of-type(3) > button}，
 *                  送进模型上下文的成本差一个数量级，而模型抄错的概率也低得多。</p>
 */
public record InteractiveElement(
        String tag,
        String text,
        String elementId) {
}
