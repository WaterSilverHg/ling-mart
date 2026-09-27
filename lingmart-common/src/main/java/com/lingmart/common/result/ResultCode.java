package com.lingmart.common.result;

import lombok.Getter;

/**
 * 统一返回状态码枚举。
 *
 * <p>通用错误码约定（各服务可在 BizException 中复用）：</p>
 * <ul>
 *   <li>2xx：成功</li>
 *   <li>400：客户端参数错误</li>
 *   <li>401：未认证 / Token 无效</li>
 *   <li>404：资源不存在</li>
 *   <li>5xx：服务端错误</li>
 *   <li>60000+：业务错误码</li>
 * </ul>
 */
@Getter
public enum ResultCode {

    /** 成功 */
    SUCCESS(200, "操作成功"),

    /** 客户端参数错误 */
    BAD_REQUEST(400, "请求参数错误"),

    /** 未认证或 Token 无效 */
    UNAUTHORIZED(401, "未登录或登录已过期"),

    /** 无权限访问 */
    FORBIDDEN(403, "无权访问"),

    /** 资源不存在 */
    NOT_FOUND(404, "资源不存在"),

    /** 请求方法不支持（如对 GET 接口发 POST） */
    METHOD_NOT_ALLOWED(405, "请求方法不支持"),

    /** 请求媒体类型不支持（Content-Type 不在接口接受范围） */
    UNSUPPORTED_MEDIA_TYPE(415, "不支持的媒体类型"),

    /** 通用业务错误 */
    BIZ_ERROR(60000, "业务处理失败"),

    /** 服务端内部错误 */
    INTERNAL_ERROR(500, "系统繁忙，请稍后再试");

    private final Integer code;
    private final String msg;

    ResultCode(Integer code, String msg) {
        this.code = code;
        this.msg = msg;
    }
}
