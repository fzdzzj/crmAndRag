package com.slz.crm.platform.token.controller;

import com.slz.crm.common.annotation.RequirePermission;
import com.slz.crm.common.enumeration.PermissionOperates;
import com.slz.crm.common.result.Result;
import com.slz.crm.platform.contract.PlatformErrorCode;
import com.slz.crm.platform.contract.TokenUsageType;
import com.slz.crm.platform.mapper.PlatformTokenUsageMapper;
import com.slz.crm.platform.token.TokenUsageByUserPageVO;
import com.slz.crm.platform.token.TokenUsageByUserVO;
import com.slz.crm.platform.token.TokenUsageQuery;
import com.slz.crm.platform.token.TokenUsageSummaryVO;
import com.slz.crm.platform.token.TokenUsageTotals;
import com.slz.crm.platform.token.TokenUsageTypeAggregate;
import com.slz.crm.platform.token.TokenUsageTypeShareVO;
import com.slz.crm.platform.token.TokenUsageUserAggregate;
import com.slz.crm.pojo.entity.UserEntity;
import com.slz.crm.server.mapper.UserMapper;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 超管「AI 用量」只读查询接口（token-usage-admin-view）。
 *
 * <p><b>权限</b>：两端点统一挂 {@code @RequirePermission(AI_TOKEN_USAGE_VIEW(810))}；810 不授业务角色，
 * 超管（roleId=1）由 {@code PermissionsInterceptor} 直通，等价「超管专享」。
 *
 * <p><b>计量口径</b>：数据源为 {@code platform_token_usage} 明细（写侧 {@code TokenUsageRecorderImpl}）。时间条件一律半开区间
 * {@code create_time >= start AND create_time < end}；区间缺省近 30 天、上限 366 天；分页上限 {@value
 * #MAX_PAGE_SIZE}。失败调用也计量， 故 {@code calls} 含失败，{@code failedCalls} 单列。
 *
 * <p><b>0 token 不是免费</b>：DashScope 文本 embedding 与视觉联合 embedding 端点不回流 usage，token 三列恒 0 （{@code
 * docs/token-metering.md} §1、§6），页面必须同时看 {@code calls}。
 */
@RestController
@RequestMapping("/ai/token-usage")
public class AiTokenUsageController {

  /** 分页上限（任务卡硬约束 ≤200）。 */
  static final int MAX_PAGE_SIZE = 200;

  private static final String USER_PREFIX = "user:";
  private static final String SYSTEM_REF = "user:system";
  private static final String UNKNOWN_REF = "user:unknown";
  private static final String SYSTEM_LABEL = "系统";
  private static final String UNKNOWN_LABEL = "未知来源";

  private final PlatformTokenUsageMapper tokenUsageMapper;
  private final UserMapper userMapper;
  private final Clock clock;

  /**
   * Spring 注入入口（系统时钟）。
   *
   * @param tokenUsageMapper 计量明细 Mapper
   * @param userMapper 用户 Mapper（批量还原展示名）
   */
  @Autowired
  public AiTokenUsageController(PlatformTokenUsageMapper tokenUsageMapper, UserMapper userMapper) {
    this(tokenUsageMapper, userMapper, Clock.systemDefaultZone());
  }

  /**
   * 显式时钟构造器，供单测固定「当前时间」。
   *
   * @param tokenUsageMapper 计量明细 Mapper
   * @param userMapper 用户 Mapper
   * @param clock 校验基准时钟
   */
  AiTokenUsageController(
      PlatformTokenUsageMapper tokenUsageMapper, UserMapper userMapper, Clock clock) {
    this.tokenUsageMapper = tokenUsageMapper;
    this.userMapper = userMapper;
    this.clock = clock;
  }

  /**
   * 按用户分页查看 token 消耗（总 token 降序）。
   *
   * @param startTime 区间起点（ISO-8601，可空；与 endTime 同时缺省 = 近 30 天）
   * @param endTime 区间终点（ISO-8601，不含；可空）
   * @param usageType 计量类型（chat/embedding/ocr/vision/summary/intent，可空）
   * @param pageNum 页码（≥1，默认 1）
   * @param pageSize 页大小（1..200，默认 20）
   * @return 分页用户用量
   */
  @GetMapping("/by-user")
  @RequirePermission(PermissionOperates.AI_TOKEN_USAGE_VIEW)
  public Result<TokenUsageByUserPageVO> byUser(
      @RequestParam(required = false) String startTime,
      @RequestParam(required = false) String endTime,
      @RequestParam(required = false) String usageType,
      @RequestParam(defaultValue = "1") Integer pageNum,
      @RequestParam(defaultValue = "20") Integer pageSize) {
    return Result.success(loadPage(startTime, endTime, usageType, pageNum, pageSize));
  }

  /**
   * 区间总览：token 三口径 + 调用/失败次数 + 去重用户数 + 按 usage_type 分组占比。
   *
   * @param startTime 区间起点（ISO-8601，可空）
   * @param endTime 区间终点（ISO-8601，不含；可空）
   * @param usageType 计量类型（可空）
   * @return 区间总览
   */
  @GetMapping("/summary")
  @RequirePermission(PermissionOperates.AI_TOKEN_USAGE_VIEW)
  public Result<TokenUsageSummaryVO> summary(
      @RequestParam(required = false) String startTime,
      @RequestParam(required = false) String endTime,
      @RequestParam(required = false) String usageType) {
    return Result.success(loadSummary(startTime, endTime, usageType));
  }

  /**
   * 参数校验失败统一转 VALIDATION(96007)，复用平台错误码，不新增枚举项。
   *
   * @param exception 校验异常
   * @return 错误结果
   */
  @ExceptionHandler(IllegalArgumentException.class)
  public Result<Void> handleValidation(IllegalArgumentException exception) {
    return Result.error(PlatformErrorCode.VALIDATION.getCode(), exception.getMessage());
  }

  private TokenUsageByUserPageVO loadPage(
      String startTime, String endTime, String usageType, Integer pageNum, Integer pageSize) {
    TokenUsageQuery query =
        TokenUsageQuery.of(startTime, endTime, usageType, LocalDateTime.now(clock));
    validatePage(pageNum, pageSize);
    long total =
        tokenUsageMapper.countUsers(query.getStart(), query.getEnd(), query.getUsageTypeNames());
    long offset = (long) (pageNum - 1) * pageSize;
    List<TokenUsageUserAggregate> aggregates =
        tokenUsageMapper.selectUserAggregates(
            query.getStart(), query.getEnd(), query.getUsageTypeNames(), pageSize, offset);
    TokenUsageTotals totals =
        tokenUsageMapper.selectTotals(query.getStart(), query.getEnd(), query.getUsageTypeNames());
    Map<Long, String> names = resolveUserNames(aggregates);
    TokenUsageByUserPageVO page = new TokenUsageByUserPageVO();
    page.setTotal(total);
    page.setRecords(assemble(aggregates, names, nz(totals.getTotalTokens())));
    return page;
  }

  private TokenUsageSummaryVO loadSummary(String startTime, String endTime, String usageType) {
    TokenUsageQuery query =
        TokenUsageQuery.of(startTime, endTime, usageType, LocalDateTime.now(clock));
    TokenUsageTotals totals =
        tokenUsageMapper.selectTotals(query.getStart(), query.getEnd(), query.getUsageTypeNames());
    long rangeTotal = nz(totals.getTotalTokens());
    List<TokenUsageTypeAggregate> typeAggregates =
        tokenUsageMapper.selectTypeAggregates(
            query.getStart(), query.getEnd(), query.getUsageTypeNames());
    TokenUsageSummaryVO summary = new TokenUsageSummaryVO();
    summary.setStartTime(query.getStart().toString());
    summary.setEndTime(query.getEnd().toString());
    summary.setPromptTokens(nz(totals.getPromptTokens()));
    summary.setCompletionTokens(nz(totals.getCompletionTokens()));
    summary.setTotalTokens(rangeTotal);
    summary.setCalls(nz(totals.getCalls()));
    summary.setFailedCalls(nz(totals.getFailedCalls()));
    summary.setUserCount(nz(totals.getUserCount()));
    summary.setUsageTypes(mergeTypes(typeAggregates, rangeTotal));
    return summary;
  }

  private void validatePage(Integer pageNum, Integer pageSize) {
    if (pageNum == null || pageNum < 1) {
      throw new IllegalArgumentException("pageNum 必须 ≥ 1");
    }
    if (pageSize == null || pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
      throw new IllegalArgumentException("pageSize 必须在 1.." + MAX_PAGE_SIZE + " 之间");
    }
  }

  private List<TokenUsageByUserVO> assemble(
      List<TokenUsageUserAggregate> aggregates, Map<Long, String> names, long rangeTotal) {
    List<TokenUsageByUserVO> result = new ArrayList<>(aggregates.size());
    for (TokenUsageUserAggregate aggregate : aggregates) {
      Long userId = parseUserId(aggregate.getUserRef());
      TokenUsageByUserVO row = new TokenUsageByUserVO();
      row.setUserRef(aggregate.getUserRef());
      row.setUserId(userId);
      row.setUserName(displayName(aggregate.getUserRef(), userId, names));
      row.setPromptTokens(nz(aggregate.getPromptTokens()));
      row.setCompletionTokens(nz(aggregate.getCompletionTokens()));
      row.setTotalTokens(nz(aggregate.getTotalTokens()));
      row.setCalls(nz(aggregate.getCalls()));
      row.setFailedCalls(nz(aggregate.getFailedCalls()));
      row.setLastCallTime(aggregate.getLastCallTime());
      row.setSharePct(percent(aggregate.getTotalTokens(), rangeTotal));
      result.add(row);
    }
    return result;
  }

  /** 批量回查真实姓名；用 IN 查询而非逐行回查，用户数随分页上限收敛到 ≤200。 */
  private Map<Long, String> resolveUserNames(List<TokenUsageUserAggregate> aggregates) {
    Set<Long> ids = new HashSet<>();
    for (TokenUsageUserAggregate aggregate : aggregates) {
      Long userId = parseUserId(aggregate.getUserRef());
      if (userId != null) {
        ids.add(userId);
      }
    }
    Map<Long, String> names = new HashMap<>();
    if (!ids.isEmpty()) {
      for (UserEntity user : userMapper.selectBatchIds(ids)) {
        names.put(user.getId(), user.getRealName());
      }
    }
    return names;
  }

  private static String displayName(String userRef, Long userId, Map<Long, String> names) {
    String result;
    if (SYSTEM_REF.equals(userRef)) {
      result = SYSTEM_LABEL;
    } else if (UNKNOWN_REF.equals(userRef)) {
      result = UNKNOWN_LABEL;
    } else if (userId == null) {
      result = userRef == null || userRef.isBlank() ? UNKNOWN_LABEL : userRef;
    } else {
      result = names.getOrDefault(userId, "已删除用户(#" + userId + ")");
    }
    return result;
  }

  private static Long parseUserId(String userRef) {
    Long result = null;
    if (userRef != null && userRef.startsWith(USER_PREFIX)) {
      String tail = userRef.substring(USER_PREFIX.length());
      if (!tail.isEmpty() && !"system".equals(tail) && !"unknown".equals(tail)) {
        try {
          result = Long.valueOf(tail);
        } catch (NumberFormatException ignored) {
          // 非数字后缀（历史脏数据）按「无法解析」处理，展示层回退到原始 userRef
          result = null;
        }
      }
    }
    return result;
  }

  /** 库内 usage_type 归一为 wire 名；历史行若写的是枚举名也接受（大小写不敏感）。 */
  private static String wireName(String rawType) {
    String result = rawType == null || rawType.isBlank() ? "unknown" : rawType;
    for (TokenUsageType type : TokenUsageType.values()) {
      if (type.wireName().equalsIgnoreCase(result) || type.name().equalsIgnoreCase(result)) {
        result = type.wireName();
      }
    }
    return result;
  }

  /** 合并同类型的不同大小写分组（历史写入口径差异），再按总 token 降序给出占比。 */
  private static List<TokenUsageTypeShareVO> mergeTypes(
      List<TokenUsageTypeAggregate> aggregates, long rangeTotal) {
    Map<String, TokenUsageTypeShareVO> merged = new LinkedHashMap<>();
    for (TokenUsageTypeAggregate aggregate : aggregates) {
      String type = wireName(aggregate.getUsageType());
      TokenUsageTypeShareVO share = merged.computeIfAbsent(type, AiTokenUsageController::newShare);
      share.setTotalTokens(nz(share.getTotalTokens()) + nz(aggregate.getTotalTokens()));
      share.setCalls(nz(share.getCalls()) + nz(aggregate.getCalls()));
      share.setFailedCalls(nz(share.getFailedCalls()) + nz(aggregate.getFailedCalls()));
    }
    List<TokenUsageTypeShareVO> result = new ArrayList<>(merged.values());
    result.sort(Comparator.comparing(TokenUsageTypeShareVO::getTotalTokens).reversed());
    for (TokenUsageTypeShareVO share : result) {
      share.setSharePct(percent(share.getTotalTokens(), rangeTotal));
    }
    return result;
  }

  private static TokenUsageTypeShareVO newShare(String usageType) {
    TokenUsageTypeShareVO share = new TokenUsageTypeShareVO();
    share.setUsageType(usageType);
    return share;
  }

  /** 占比（百分比，两位小数）；区间总量为 0 时返回 0，避免除零。 */
  private static double percent(Long part, long whole) {
    double result = 0.0;
    if (whole > 0 && part != null) {
      result = Math.round(part * 10000.0 / whole) / 100.0;
    }
    return result;
  }

  private static long nz(Long value) {
    return value == null ? 0L : value;
  }
}
