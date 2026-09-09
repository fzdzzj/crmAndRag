package com.slz.crm.common.exiception;

import com.slz.crm.common.enumeration.ErrorCode;

/**
 * 业务异常
 */
public class BaseException extends RuntimeException {

    private Integer code;

    public BaseException() {}

    /**
     * 业务异常
     * @param message 异常信息
     */
    public BaseException(String message) {
        super(message);
    }

    /**
     * 业务异常
     * @param code 错误码
     * @param message 异常信息
     */
    public BaseException(Integer code, String message) {
        super(message);
        this.code = code;
    }

    /**
     * 业务异常
     * @param errorCode 错误码枚举
     */
    public BaseException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.code = errorCode.getCode();
    }

    /**
     * 业务异常
     * @param errorCode 错误码枚举
     * @param message 自定义异常信息
     */
    public BaseException(ErrorCode errorCode, String message) {
        super(message);
        this.code = errorCode.getCode();
    }

    public Integer getCode() {
        return code;
    }

    public void setCode(Integer code) {
        this.code = code;
    }

}
