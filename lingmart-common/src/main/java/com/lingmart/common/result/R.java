package com.lingmart.common.result;

import com.lingmart.common.exception.BizException;
import lombok.Data;

import java.io.Serializable;

/**
 * 统一接口返回结果包装类。
 *
 * <p>所有服务接口统一返回该结构，格式为：{@code { code, msg, data }}。
 * 前端可根据 code 判断业务是否成功，msg 为提示信息，data 为业务数据。</p>
 *
 * @param <T> 业务数据类型
 */
@Data
public class R<T> implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 状态码：200 表示成功，其余为业务/系统错误码 */
    private Integer code;

    /** 提示信息 */
    private String msg;

    /** 业务数据 */
    private T data;

    /**
     * 构造成功响应（无数据）。
     *
     * @return 成功响应
     */
    public static <T> R<T> ok() {
        return build(ResultCode.SUCCESS.getCode(), ResultCode.SUCCESS.getMsg(), null);
    }

    /**
     * 构造成功响应（携带数据）。
     *
     * @param data 业务数据
     * @return 成功响应
     */
    public static <T> R<T> ok(T data) {
        return build(ResultCode.SUCCESS.getCode(), ResultCode.SUCCESS.getMsg(), data);
    }

    /**
     * 构造失败响应（使用默认失败信息）。
     *
     * @return 失败响应
     */
    public static <T> R<T> fail() {
        return build(ResultCode.INTERNAL_ERROR.getCode(), ResultCode.INTERNAL_ERROR.getMsg(), null);
    }

    /**
     * 构造失败响应（自定义提示信息，默认业务码）。
     *
     * @param msg 提示信息
     * @return 失败响应
     */
    public static <T> R<T> fail(String msg) {
        return build(ResultCode.BIZ_ERROR.getCode(), msg, null);
    }

    /**
     * 构造失败响应（自定义状态码与提示信息）。
     *
     * @param code 状态码
     * @param msg  提示信息
     * @return 失败响应
     */
    public static <T> R<T> fail(Integer code, String msg) {
        return build(code, msg, null);
    }

    /**
     * 内部统一组装方法。
     *
     * @param code 状态码
     * @param msg  提示信息
     * @param data 业务数据
     * @return 组装完成的响应
     */
    private static <T> R<T> build(Integer code, String msg, T data) {
        R<T> r = new R<>();
        r.setCode(code);
        r.setMsg(msg);
        r.setData(data);
        return r;
    }

    /**
     * 校验下游 Feign 返回结果：code 非 200 或无响应时抛出业务异常。
     *
     * <p>下游全局异常处理器会把业务异常转成 HTTP 200 + R{code:非200} 返回，
     * Feign 不会因此抛异常；若调用方忽略返回值会静默吞掉业务错误。
     * 在关键写操作（锁库存/扣减/解锁/批量建商品等）调用点必须用本方法校验。</p>
     *
     * @param r Feign 返回
     * @param <T> 业务数据类型
     * @return 成功时的 data（可能为 null，表示合法的空数据）
     */
    public static <T> T checkOk(R<T> r) {
        if (r == null) {
            throw new BizException("下游服务无响应");
        }
        // 注意：code 是 Integer，必须用 equals 而非 !=（Integer 缓存仅覆盖 -128~127，
        // 200 不在缓存内，两个 Integer(200) 用 != 比较引用会恒为 true，导致下游明明成功却被判失败）
        if (r.getCode() == null || !ResultCode.SUCCESS.getCode().equals(r.getCode())) {
            throw new BizException(r.getMsg() == null ? "下游服务处理失败" : r.getMsg());
        }
        return r.getData();
    }
}
