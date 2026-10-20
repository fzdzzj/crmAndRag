package com.slz.crm.common.untils;

import com.slz.crm.common.enumeration.ErrorCode;
import com.slz.crm.common.exiception.BaseException;
import java.util.List;
import java.util.Objects;

/**
 * 批量 ID 入参统一闸（fix-backlog-batch3 任务 2.1/2.2）。
 *
 * <p>为什么要有这道闸：16 个批量端点把 {@code List<Long>} 全量塞进 MyBatis-Plus 的 {@code IN (...)}，空集既生成非法 SQL
 * 又白跑一趟往返，超长列表则让单条 SQL 的文本量与占位符数量无界增长。本闸在控制器入口一次性拒绝这两类入参；服务层既有校验 保持不变（双闸不冲突，先命中者生效，错误码同源）。
 *
 * <p>为什么不自动分批：批量删除分批会把一次调用拆成多个事务，产生「前半部分已提交、后半部分失败」的中间态；超限硬拒让调用方 自行分批重试，事务边界留在调用方手里。
 *
 * <p>{@link #MAX_BATCH_IDS} = 500 的选型依据：①500 个 {@code long} 绑定参数约 4KB 文本，是「一次往返仍能扛住」的量级 （MySQL
 * 占位符上限远大于它，故上限不是驱动约束而是业务约束）；②CRM 现有批量入口的实际用例（导出勾选、批量回收站、 批量转派）实测分布在 10-200 之间，500 留出约 2.5
 * 倍余量；③再往上（数千级）该走「按条件批处理」而不是「按 id 清单」， 接口形态本身就该换，因此不把上限定在 1000+。
 */
public final class BatchIdsGuard {

  /** 单次批量请求允许的 id 数上限（去重后计数），选型依据见类注释。 */
  public static final int MAX_BATCH_IDS = 500;

  private BatchIdsGuard() {
    throw new AssertionError("Utility class cannot be instantiated");
  }

  /**
   * 校验批量 id 入参：null/空集/全 null 元素 → {@code PARAM_EMPTY}；distinct 后超 {@link #MAX_BATCH_IDS} → {@code
   * PARAM_OUT_OF_RANGE}（文案带上限）。
   *
   * @param ids 批量 id 清单（可为 null）
   * @throws BaseException 入参不满足批量端点前置条件时抛出
   */
  public static void check(List<Long> ids) {
    if (ids == null || ids.isEmpty()) {
      throw new BaseException(ErrorCode.PARAM_EMPTY);
    }
    // 去重后计数：IN 列表的实际元素个数才是 SQL 规模的真实口径，重复 id 不应被算作两次
    long distinctCount = ids.stream().filter(Objects::nonNull).distinct().count();
    if (distinctCount == 0) {
      throw new BaseException(ErrorCode.PARAM_EMPTY);
    }
    if (distinctCount > MAX_BATCH_IDS) {
      throw new BaseException(
          ErrorCode.PARAM_OUT_OF_RANGE,
          "批量ID数量超限：去重后最多允许 " + MAX_BATCH_IDS + " 个，实际 " + distinctCount + " 个");
    }
  }
}
