package com.lingmart.common.result;

import com.lingmart.common.exception.BizException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 统一返回结果 R 与下游校验 checkOk 的单元测试。
 */
class RTest {

    @Test
    void ok_返回成功码与数据() {
        R<String> r = R.ok("hello");
        assertEquals(200, r.getCode());
        assertEquals("hello", r.getData());
    }

    @Test
    void fail_携带可读信息() {
        R<Void> r = R.fail(60000, "业务失败");
        assertEquals(60000, r.getCode());
        assertEquals("业务失败", r.getMsg());
    }

    /** 下游返回业务失败 → checkOk 抛 BizException 并透传下游提示（不静默吞错） */
    @Test
    void checkOk_下游业务失败抛异常() {
        R<Void> r = R.fail(60000, "商品未上架或未通过审核，无法购买");
        BizException ex = assertThrows(BizException.class, () -> R.checkOk(r));
        assertEquals("商品未上架或未通过审核，无法购买", ex.getMessage());
    }

    /** 下游无响应（Feign 返回 null）→ checkOk 抛异常 */
    @Test
    void checkOk_下游无响应抛异常() {
        assertThrows(BizException.class, () -> R.checkOk(null));
    }

    /** 下游成功且 data 为 null（合法空数据）→ 正常返回 null 不抛错 */
    @Test
    void checkOk_成功但数据为空不抛错() {
        R<Void> r = R.ok();
        assertNull(R.checkOk(r));
    }

    /**
     * 回归：下游 code=200 经 Feign/Jackson 反序列化为「新的 Integer 实例」，
     * 与 SUCCESS 常量引用不同。历史上用 {@code r.getCode() != SUCCESS.getCode()} 比较
     * 引用（Integer 缓存仅 -128~127，200 不在缓存），会把成功误判为失败，导致
     * 开店申请等跨服务写操作被 Seata 回滚（店铺不入表）。必须用 equals 比较。
     */
    @Test
    void checkOk_成功码为独立Integer实例不误判失败() {
        R<Void> r = new R<>();
        r.setCode(Integer.valueOf(200));   // 模拟 Feign 反序列化产生的新实例
        r.setMsg("操作成功");
        assertNull(R.checkOk(r));
    }
}
