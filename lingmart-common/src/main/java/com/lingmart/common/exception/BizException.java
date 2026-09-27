package com.lingmart.common.exception;

import com.lingmart.common.result.ResultCode;
import lombok.Getter;

/**
 * 业务异常。
 *
 * <p>业务逻辑校验失败时抛出，由 {@link GlobalExceptionHandler} 统一捕获并转换为
 * 带业务错误码的 {@code R} 响应。例如：手机号已注册、验证码错误等。</p>
 */
@Getter
public class BizException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** 业务错误码 */
    private final Integer code;

    /**
     * 构造业务异常（默认业务错误码 {@link ResultCode#BIZ_ERROR}）。
     *
     * @param message 错误提示信息
     */
    public BizException(String message) {
        super(message);
        this.code = ResultCode.BIZ_ERROR.getCode();
    }

    /**
     * 构造业务异常（自定义错误码与提示信息）。
     *
     * @param code    业务错误码
     * @param message 错误提示信息
     */
    public BizException(Integer code, String message) {
        super(message);
        this.code = code;
    }
}
