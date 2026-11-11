package com.lingmart.common.exception;

import com.lingmart.common.constant.AuthConstants;
import com.lingmart.common.result.R;
import com.lingmart.common.result.ResultCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

/**
 * 全局异常处理器。
 *
 * <p>统一捕获 Controller 抛出的各类异常，转换为标准 {@link R} 响应返回前端，
 * 避免异常信息直接暴露给调用方。按异常类型细化为 400（参数/请求体/类型）、
 * 403（无权限）、404（不存在）、405（方法不支持）、415（媒体类型不支持）等语义，
 * 前端可依据 code 精确提示，而非一律「系统繁忙」。</p>
 *
 * <p>注意：该类基于 Servlet 实现，仅用于普通业务服务（如 auth），网关（WebFlux）请勿扫描此包。</p>
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 处理业务异常。
     *
     * <p><b>日志为什么带上 method/path/userId</b>：只打 code + msg 时，"库存不足"这四个字
     * 既不知道发生在哪个接口，也不知道是谁触发的；压测报告里出现"下单失败 N 个"就只能靠猜。
     * 带上这三个字段后，可以直接按接口与用户聚合，再配合 traceId 串到 Zipkin 与上下游日志。
     * 注意仍**不打印请求体**（可能含用户输入），只记录路由与身份。</p>
     *
     * @param e       业务异常
     * @param request 当前请求（用于补充路由与用户）
     * @return 携带业务错误码的失败响应
     */
    @ExceptionHandler(BizException.class)
    public R<Void> handleBizException(BizException e, HttpServletRequest request) {
        log.warn("业务异常：code={}, msg={}, {} {}, userId={}",
                e.getCode(), e.getMessage(), request.getMethod(), request.getRequestURI(),
                request.getHeader(AuthConstants.HEADER_USER_ID));
        return R.fail(e.getCode(), e.getMessage());
    }

    /**
     * 处理 {@code @Valid} 请求体校验异常（@RequestBody 上的 Bean Validation）。
     *
     * @param e 参数校验异常
     * @return 首个校验失败字段的提示信息
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public R<Void> handleValidException(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(error -> error.getField() + " " + error.getDefaultMessage())
                .orElse(ResultCode.BAD_REQUEST.getMsg());
        log.warn("参数校验失败：{}", msg);
        return R.fail(ResultCode.BAD_REQUEST.getCode(), msg);
    }

    /**
     * 处理表单/模型绑定异常（非 @RequestBody 的绑定失败，如 @ModelAttribute）。
     *
     * @param e 绑定异常
     * @return 400 提示
     */
    @ExceptionHandler(BindException.class)
    public R<Void> handleBindException(BindException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(error -> error.getField() + " " + error.getDefaultMessage())
                .orElse(ResultCode.BAD_REQUEST.getMsg());
        log.warn("绑定失败：{}", msg);
        return R.fail(ResultCode.BAD_REQUEST.getCode(), msg);
    }

    /**
     * 处理方法级参数约束异常（@RequestParam/@PathVariable 上的 {@code @Min} 等）。
     *
     * @param e 约束违例异常
     * @return 400 提示
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public R<Void> handleConstraintViolation(ConstraintViolationException e) {
        String msg = e.getConstraintViolations().stream()
                .findFirst()
                .map(v -> v.getPropertyPath() + " " + v.getMessage())
                .orElse(ResultCode.BAD_REQUEST.getMsg());
        log.warn("参数约束失败：{}", msg);
        return R.fail(ResultCode.BAD_REQUEST.getCode(), msg);
    }

    /**
     * 处理请求体不可读（JSON 解析失败/缺请求体）。
     *
     * @param e 请求体不可读异常
     * @return 400 提示
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public R<Void> handleNotReadable(HttpMessageNotReadableException e) {
        log.warn("请求体解析失败：{}", e.getMessage());
        return R.fail(ResultCode.BAD_REQUEST.getCode(), "请求参数格式不正确");
    }

    /**
     * 处理缺少必填请求参数。
     *
     * @param e 缺少参数异常
     * @return 400 提示（带参数名）
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public R<Void> handleMissingParam(MissingServletRequestParameterException e) {
        String msg = "缺少必填参数：" + e.getParameterName();
        log.warn("缺少请求参数：{}", e.getParameterName());
        return R.fail(ResultCode.BAD_REQUEST.getCode(), msg);
    }

    /**
     * 处理请求参数类型不匹配（如把非数字传给 Long 参数）。
     *
     * @param e 类型不匹配异常
     * @return 400 提示
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public R<Void> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        String msg = "参数 " + e.getName() + " 类型不正确";
        log.warn("参数类型不匹配：name={}, value={}", e.getName(), e.getValue());
        return R.fail(ResultCode.BAD_REQUEST.getCode(), msg);
    }

    /**
     * 处理文件上传超限。
     *
     * @param e 上传超限异常
     * @return 400 提示
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public R<Void> handleMaxUpload(MaxUploadSizeExceededException e) {
        log.warn("上传文件超限：{}", e.getMessage());
        return R.fail(ResultCode.BAD_REQUEST.getCode(), "上传文件大小超过限制");
    }

    /**
     * 处理 HTTP 方法不支持（如对 GET 接口发 POST）。
     *
     * @param e 方法不支持异常
     * @return 405 提示
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public R<Void> handleMethodNotSupported(HttpRequestMethodNotSupportedException e) {
        log.warn("请求方法不支持：{} {}", e.getMethod(), e.getSupportedHttpMethods());
        return R.fail(ResultCode.METHOD_NOT_ALLOWED.getCode(), ResultCode.METHOD_NOT_ALLOWED.getMsg());
    }

    /**
     * 处理媒体类型不支持（Content-Type 不在接口接受范围）。
     *
     * @param e 媒体类型不支持异常
     * @return 415 提示
     */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public R<Void> handleMediaTypeNotSupported(HttpMediaTypeNotSupportedException e) {
        log.warn("请求媒体类型不支持：{}", e.getContentType());
        return R.fail(ResultCode.UNSUPPORTED_MEDIA_TYPE.getCode(), ResultCode.UNSUPPORTED_MEDIA_TYPE.getMsg());
    }

    /**
     * 处理兜底异常（未知异常）。
     *
     * @param e 未知异常
     * @return 通用失败响应
     */
    @ExceptionHandler(Exception.class)
    public R<Void> handleException(Exception e) {
        log.error("系统异常", e);
        return R.fail(ResultCode.INTERNAL_ERROR.getCode(), ResultCode.INTERNAL_ERROR.getMsg());
    }
}
