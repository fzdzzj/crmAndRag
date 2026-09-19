package com.slz.crm.common.exiception;

import com.slz.crm.common.enumeration.ErrorCode;

/** 服务器异常 */
public class ServiceException extends RuntimeException {
  private Integer code;

  public ServiceException() {}

  public ServiceException(String message) {
    super(message);
  }

  public ServiceException(String message, Throwable cause) {
    super(message, cause);
  }

  /**
   * 服务器异常
   *
   * @param code 错误码
   * @param message 异常信息
   */
  public ServiceException(Integer code, String message) {
    super(message);
    this.code = code;
  }

  /**
   * 服务器异常
   *
   * @param errorCode 错误码枚举
   */
  public ServiceException(ErrorCode errorCode) {
    super(errorCode.getMessage());
    this.code = errorCode.getCode();
  }

  /**
   * 服务器异常
   *
   * @param errorCode 错误码枚举
   * @param message 自定义异常信息
   */
  public ServiceException(ErrorCode errorCode, String message) {
    super(message);
    this.code = errorCode.getCode();
  }

  /**
   * 服务器异常
   *
   * @param errorCode 错误码枚举
   * @param cause 异常原因
   */
  public ServiceException(ErrorCode errorCode, Throwable cause) {
    super(errorCode.getMessage(), cause);
    this.code = errorCode.getCode();
  }

  /**
   * 服务器异常
   *
   * @param errorCode 错误码枚举
   * @param message 自定义异常信息
   * @param cause 异常原因
   */
  public ServiceException(ErrorCode errorCode, String message, Throwable cause) {
    super(message, cause);
    this.code = errorCode.getCode();
  }

  public Integer getCode() {
    return code;
  }

  public void setCode(Integer code) {
    this.code = code;
  }
}
