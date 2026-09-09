package com.slz.crm.common.untils;

import com.slz.crm.common.exiception.BaseException;
import com.slz.crm.common.enumeration.ErrorCode;

/**
 * 分页参数验证工具类
 */
public class PageValidationUtils {

    /**
     * 默认页码
     */
    public static final int DEFAULT_PAGE_NUM = 1;

    /**
     * 默认页大小
     */
    public static final int DEFAULT_PAGE_SIZE = 10;

    /**
     * 最小页码
     */
    public static final int MIN_PAGE_NUM = 1;

    /**
     * 最大页大小（防止大分页查询导致内存溢出）
     */
    public static final int MAX_PAGE_SIZE = 100;

    /**
     * 验证并修正分页参数
     * @param pageNum 页码
     * @param pageSize 页大小
     * @return 修正后的分页参数数组 [pageNum, pageSize]
     */
    public static int[] validateAndFix(Integer pageNum, Integer pageSize) {
        // 处理null值，使用默认值
        int validPageNum = (pageNum == null || pageNum < MIN_PAGE_NUM) ? DEFAULT_PAGE_NUM : pageNum;
        int validPageSize = (pageSize == null || pageSize < 1) ? DEFAULT_PAGE_SIZE : pageSize;

        // 验证页大小上限
        if (validPageSize > MAX_PAGE_SIZE) {
            throw new BaseException(ErrorCode.PARAM_FORMAT_ERROR,
                    String.format("页大小超过最大限制，最大页大小为 %d，请求的页大小为 %d", MAX_PAGE_SIZE, validPageSize));
        }

        return new int[]{validPageNum, validPageSize};
    }

    /**
     * 验证分页参数（不修正）
     * @param pageNum 页码
     * @param pageSize 页大小
     * @throws BaseException 当参数不合法时抛出异常
     */
    public static void validate(Integer pageNum, Integer pageSize) {
        if (pageNum == null) {
            throw new BaseException(ErrorCode.PARAM_EMPTY, "页码不能为空");
        }
        if (pageNum < MIN_PAGE_NUM) {
            throw new BaseException(ErrorCode.PARAM_FORMAT_ERROR,
                    String.format("页码必须大于等于 %d，当前页码为 %d", MIN_PAGE_NUM, pageNum));
        }
        if (pageSize == null) {
            throw new BaseException(ErrorCode.PARAM_EMPTY, "页大小不能为空");
        }
        if (pageSize < 1) {
            throw new BaseException(ErrorCode.PARAM_FORMAT_ERROR,
                    String.format("页大小必须大于等于 1，当前页大小为 %d", pageSize));
        }
        if (pageSize > MAX_PAGE_SIZE) {
            throw new BaseException(ErrorCode.PARAM_FORMAT_ERROR,
                    String.format("页大小超过最大限制，最大页大小为 %d，请求的页大小为 %d", MAX_PAGE_SIZE, pageSize));
        }
    }

    /**
     * 获取默认页码
     * @return 默认页码
     */
    public static int getDefaultPageNum() {
        return DEFAULT_PAGE_NUM;
    }

    /**
     * 获取默认页大小
     * @return 默认页大小
     */
    public static int getDefaultPageSize() {
        return DEFAULT_PAGE_SIZE;
    }

    /**
     * 获取最大页大小
     * @return 最大页大小
     */
    public static int getMaxPageSize() {
        return MAX_PAGE_SIZE;
    }
}
