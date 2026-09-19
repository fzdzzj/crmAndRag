package com.slz.crm.server.handler;

import com.slz.crm.common.enumeration.ErrorCode;
import com.slz.crm.common.exiception.BaseException;
import com.slz.crm.common.exiception.ServiceException;
import com.slz.crm.common.result.Result;
import com.slz.crm.platform.trace.RequestTraceKey;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Objects;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

  @ExceptionHandler(ServiceException.class)
  public Result<String> serviceException(ServiceException e) {
    // 业务异常记录完整错误信息和堆栈
    if (e.getCode() != null) {
      log.error("业务异常：错误码={}, 消息={}", e.getCode(), e.getMessage(), e);
    } else {
      log.error("业务异常：消息={}", e.getMessage(), e);
    }

    // 如果异常中有错误码，使用错误码返回
    if (e.getCode() != null) {
      ErrorCode errorCode = ErrorCode.getByCode(e.getCode());
      if (errorCode != null) {
        return Result.error(errorCode);
      }
      return Result.error(e.getCode(), e.getMessage());
    }

    return Result.error(ErrorCode.PARAM_FORMAT_ERROR);
  }

  @ExceptionHandler(BaseException.class)
  public Result<String> exceptionHandler(BaseException ex, HttpServletRequest request) {
    // 业务异常只记录错误信息，不打印完整堆栈
    if (ex.getCode() != null) {
      log.error("业务异常：{}，错误码={}, 消息={}", traceContext(request), ex.getCode(), ex.getMessage());
    } else {
      log.error("业务异常：{}，消息={}", traceContext(request), ex.getMessage());
    }

    // 如果异常中有错误码，使用错误码返回
    if (ex.getCode() != null) {
      ErrorCode errorCode = ErrorCode.getByCode(ex.getCode());
      if (errorCode != null) {
        // 如果自定义消息与错误码默认消息不同，使用自定义消息
        if (ex.getMessage() != null && !ex.getMessage().equals(errorCode.getMessage())) {
          return Result.error(ex.getCode(), ex.getMessage());
        }
        return Result.error(errorCode);
      }
      return Result.error(ex.getCode(), ex.getMessage());
    }

    return Result.error(ex.getMessage());
  }

  @ExceptionHandler(MethodArgumentTypeMismatchException.class)
  public Result<String> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
    // 获取参数名和期望类型
    String paramName = e.getName();
    String expectedType = Objects.requireNonNull(e.getRequiredType()).getSimpleName();
    String value = e.getValue() != null ? e.getValue().toString() : "null";
    String format = String.format("参数 [%s] 格式错误，期望类型：%s，实际值：%s", paramName, expectedType, value);

    return Result.error(ErrorCode.PARAM_FORMAT_ERROR, format);
  }

  /** 请求体 Bean Validation 失败时返回统一提示，不透出校验器内部细节。 */
  @ExceptionHandler(MethodArgumentNotValidException.class)
  public Result<String> handleMethodArgumentNotValid(MethodArgumentNotValidException e) {
    log.warn(
        "参数校验失败：{}",
        e.getBindingResult().getAllErrors().stream()
            .findFirst()
            .map(error -> error.getDefaultMessage())
            .orElse("参数格式错误"));
    return Result.error(ErrorCode.PARAM_FORMAT_ERROR, "参数校验失败");
  }

  /** 查询参数方法级校验失败时返回统一提示。 */
  @ExceptionHandler(HandlerMethodValidationException.class)
  public Result<String> handleHandlerMethodValidation(HandlerMethodValidationException e) {
    log.warn("参数校验失败：{}", e.getMessage());
    return Result.error(ErrorCode.PARAM_FORMAT_ERROR, "参数校验失败");
  }

  @ExceptionHandler(HttpMessageNotReadableException.class)
  public Result<String> handleHttpMessageNotReadable(HttpMessageNotReadableException e) {
    // 提取错误信息（如 "Cannot deserialize value of type `java.time.LocalDate` from String..."）
    String message = e.getMessage();
    // 简化提示（可根据实际需求优化）
    if (message.contains("LocalDate")) {
      message = "日期格式错误，期望格式：yyyy-MM-dd HH:mm:ss";
    }
    return Result.error(ErrorCode.PARAM_FORMAT_ERROR, message);
  }

  /** 处理其他未捕获的异常 */
  @ExceptionHandler(Exception.class)
  public Result<String> handleException(Exception e, HttpServletRequest request) {
    log.error("系统异常：{}", traceContext(request), e);
    return Result.error(ErrorCode.INTERNAL_SERVER_ERROR);
  }

  /** 出口日志定位串：仅取 URI 不带查询串，避免把令牌一类的参数值写进日志。 */
  private static String traceContext(HttpServletRequest request) {
    String traceId = MDC.get(RequestTraceKey.TRACE_ID);
    return "traceId="
        + (traceId == null ? "-" : traceId)
        + ", 端点="
        + request.getMethod()
        + " "
        + request.getRequestURI();
  }
}
