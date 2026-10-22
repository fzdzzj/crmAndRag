package com.slz.crm.platform.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.slz.crm.platform.token.PlatformTokenUsageEntity;
import com.slz.crm.platform.token.TokenUsageTotals;
import com.slz.crm.platform.token.TokenUsageTypeAggregate;
import com.slz.crm.platform.token.TokenUsageUserAggregate;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * Token 计量明细 Mapper。
 *
 * <p>写侧沿用 {@link BaseMapper}；token-usage-admin-view 新增三条只读聚合（按用户 / 总量 / 按 usage_type）。 全部走既有索引
 * {@code idx_platform_token_user_time (user_id_ref, create_time)} 与 {@code
 * idx_platform_token_type_time (usage_type, create_time)}：时间条件一律半开区间 {@code create_time >= start
 * AND create_time < end}，SQL 全参数化，无字符串拼接。
 */
public interface PlatformTokenUsageMapper extends BaseMapper<PlatformTokenUsageEntity> {

  /**
   * 按用户聚合（分页，total 降序）。
   *
   * @param start 起始时间（含）
   * @param end 结束时间（不含）
   * @param usageTypeNames usage_type 过滤值（空 = 不过滤）；同时带枚举名与小写 wire 名，兼容历史写入口径
   * @param limit 页大小（调用方已限幅 ≤200）
   * @param offset 偏移量
   * @return 每个 user_id_ref 一行聚合
   */
  @Select(
      "<script>"
          + "SELECT user_id_ref AS userRef,"
          + " COALESCE(SUM(prompt_tokens), 0) AS promptTokens,"
          + " COALESCE(SUM(completion_tokens), 0) AS completionTokens,"
          + " COALESCE(SUM(total_tokens), 0) AS totalTokens,"
          + " COUNT(*) AS calls,"
          + " COALESCE(SUM(CASE WHEN success = 0 THEN 1 ELSE 0 END), 0) AS failedCalls,"
          + " MAX(create_time) AS lastCallTime"
          + " FROM platform_token_usage"
          + " WHERE create_time &gt;= #{start} AND create_time &lt; #{end}"
          + "<if test='usageTypeNames != null'>"
          + " AND usage_type IN"
          + "<foreach collection='usageTypeNames' item='typeName' open='(' separator=',' close=')'>"
          + "#{typeName}"
          + "</foreach>"
          + "</if>"
          + " GROUP BY user_id_ref"
          + " ORDER BY totalTokens DESC, user_id_ref ASC"
          + " LIMIT #{limit} OFFSET #{offset}"
          + "</script>")
  List<TokenUsageUserAggregate> selectUserAggregates(
      @Param("start") LocalDateTime start,
      @Param("end") LocalDateTime end,
      @Param("usageTypeNames") List<String> usageTypeNames,
      @Param("limit") int limit,
      @Param("offset") long offset);

  /**
   * 区间内参与聚合的用户数（分页 total，与 {@link #selectUserAggregates} 同过滤条件）。
   *
   * @param start 起始时间（含）
   * @param end 结束时间（不含）
   * @param usageTypeNames usage_type 过滤值（空 = 不过滤）
   * @return 去重用户数
   */
  @Select(
      "<script>"
          + "SELECT COUNT(DISTINCT user_id_ref)"
          + " FROM platform_token_usage"
          + " WHERE create_time &gt;= #{start} AND create_time &lt; #{end}"
          + "<if test='usageTypeNames != null'>"
          + " AND usage_type IN"
          + "<foreach collection='usageTypeNames' item='typeName' open='(' separator=',' close=')'>"
          + "#{typeName}"
          + "</foreach>"
          + "</if>"
          + "</script>")
  long countUsers(
      @Param("start") LocalDateTime start,
      @Param("end") LocalDateTime end,
      @Param("usageTypeNames") List<String> usageTypeNames);

  /**
   * 区间总览（总 token / 总调用 / 失败调用 / 用户数）。
   *
   * @param start 起始时间（含）
   * @param end 结束时间（不含）
   * @param usageTypeNames usage_type 过滤值（空 = 不过滤）
   * @return 区间汇总
   */
  @Select(
      "<script>"
          + "SELECT COALESCE(SUM(prompt_tokens), 0) AS promptTokens,"
          + " COALESCE(SUM(completion_tokens), 0) AS completionTokens,"
          + " COALESCE(SUM(total_tokens), 0) AS totalTokens,"
          + " COUNT(*) AS calls,"
          + " COALESCE(SUM(CASE WHEN success = 0 THEN 1 ELSE 0 END), 0) AS failedCalls,"
          + " COUNT(DISTINCT user_id_ref) AS userCount"
          + " FROM platform_token_usage"
          + " WHERE create_time &gt;= #{start} AND create_time &lt; #{end}"
          + "<if test='usageTypeNames != null'>"
          + " AND usage_type IN"
          + "<foreach collection='usageTypeNames' item='typeName' open='(' separator=',' close=')'>"
          + "#{typeName}"
          + "</foreach>"
          + "</if>"
          + "</script>")
  TokenUsageTotals selectTotals(
      @Param("start") LocalDateTime start,
      @Param("end") LocalDateTime end,
      @Param("usageTypeNames") List<String> usageTypeNames);

  /**
   * 按 usage_type 分组的区间用量（摘要页头部占比）。
   *
   * @param start 起始时间（含）
   * @param end 结束时间（不含）
   * @param usageTypeNames usage_type 过滤值（空 = 不过滤）
   * @return 每个 usage_type 一行
   */
  @Select(
      "<script>"
          + "SELECT usage_type AS usageType,"
          + " COALESCE(SUM(total_tokens), 0) AS totalTokens,"
          + " COUNT(*) AS calls,"
          + " COALESCE(SUM(CASE WHEN success = 0 THEN 1 ELSE 0 END), 0) AS failedCalls"
          + " FROM platform_token_usage"
          + " WHERE create_time &gt;= #{start} AND create_time &lt; #{end}"
          + "<if test='usageTypeNames != null'>"
          + " AND usage_type IN"
          + "<foreach collection='usageTypeNames' item='typeName' open='(' separator=',' close=')'>"
          + "#{typeName}"
          + "</foreach>"
          + "</if>"
          + " GROUP BY usage_type"
          + " ORDER BY totalTokens DESC, usage_type ASC"
          + "</script>")
  List<TokenUsageTypeAggregate> selectTypeAggregates(
      @Param("start") LocalDateTime start,
      @Param("end") LocalDateTime end,
      @Param("usageTypeNames") List<String> usageTypeNames);
}
