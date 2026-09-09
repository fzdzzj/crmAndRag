package com.slz.crm.common.result;

import com.slz.crm.common.enumeration.ErrorCode;
import lombok.Data;

import java.io.Serializable;

/**
 * 后端统一返回结果
 * @param <T>
 */
@Data
public class Result<T> implements Serializable {

    private Integer code;
    private String msg;
    private T data;

    public static <T> Result<T> success() {
        Result<T> result = new Result<>();
        result.code = 1;
        return result;
    }

    public static <T> Result<T> success(T object) {
        Result<T> result = new Result<>();
        result.data = object;
        result.code = 1;
        return result;
    }

    public static <T> Result<T> error(String msg) {
        Result<T> result = new Result<>();
        result.msg = msg;
        result.code = 0;
        return result;
    }

    /**
     * 错误返回结果（使用错误码枚举）
     * @param errorCode 错误码枚举
     * @param <T> 泛型类型
     * @return 错误结果
     */
    public static <T> Result<T> error(ErrorCode errorCode) {
        Result<T> result = new Result<>();
        result.code = errorCode.getCode();
        result.msg = errorCode.getMessage();
        return result;
    }

    /**
     * 错误返回结果（使用错误码枚举，自定义消息）
     * @param errorCode 错误码枚举
     * @param customMsg 自定义错误消息
     * @param <T> 泛型类型
     * @return 错误结果
     */
    public static <T> Result<T> error(ErrorCode errorCode, String customMsg) {
        Result<T> result = new Result<>();
        result.code = errorCode.getCode();
        result.msg = customMsg;
        return result;
    }

    /**
     * 错误返回结果（使用错误码，自定义消息）
     * @param code 错误码
     * @param message 错误消息
     * @param <T> 泛型类型
     * @return 错误结果
     */
    public static <T> Result<T> error(Integer code, String message) {
        Result<T> result = new Result<>();
        result.code = code;
        result.msg = message;
        return result;
    }

}
