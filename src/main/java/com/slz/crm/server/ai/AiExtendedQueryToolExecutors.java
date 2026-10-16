package com.slz.crm.server.ai;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.slz.crm.common.untils.BaseUnit;
import com.slz.crm.pojo.ao.RoleAO;
import com.slz.crm.pojo.dto.GetUserDTO;
import com.slz.crm.pojo.entity.CompanyGroupEntity;
import com.slz.crm.pojo.entity.SysDeptEntity;
import com.slz.crm.pojo.vo.SalesOpportunityVO;
import com.slz.crm.pojo.vo.UserVO;
import com.slz.crm.server.service.CustomerCompanyService;
import com.slz.crm.server.service.ReportService;
import com.slz.crm.server.service.SysDeptService;
import com.slz.crm.server.service.UserService;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.stereotype.Component;

/**
 * extend-ai-query-tools-c C 批查询工具执行器：承载系统用户 / 组织架构部门 / 客户公司详情 / 统计报表 4 个查询工具的 执行细节，由 {@link
 * AiQueryToolExecutors} 委派调用；拆出独立类以使其类 NCSS 保持在该域 PMD 规约上限内（沿用 {@link AiDraftToolExecutors}
 * 的按域拆分先例）。
 */
@Component
@RequiredArgsConstructor
public class AiExtendedQueryToolExecutors {

  private static final DateTimeFormatter DATE_TIME_FORMAT =
      DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

  private final UserService userService;
  private final SysDeptService sysDeptService;
  private final CustomerCompanyService customerCompanyService;
  private final ReportService reportService;

  /**
   * 按条件分页查询系统内部用户。
   *
   * <p>add-queryuser-dept-isolation：非豁免操作者仅返回本部门（deptId）用户；豁免角色保持全量。当前操作用户经 {@link BaseUnit}（由
   * {@link AiToolCallbackFactory} 在工具执行前填充），与既有数据权限切面同一来源。
   */
  public Object executeQueryUser(Map<String, Object> args, ToolContext toolContext) {
    GetUserDTO dto = new GetUserDTO();
    dto.setRealName((String) args.get("realName"));
    dto.setPhone((String) args.get("phone"));
    dto.setEmail((String) args.get("email"));
    dto.setRoleName((String) args.get("roleName"));
    Object roleId = args.get("roleId");
    if (roleId instanceof Number number) {
      dto.setRoleId(List.of(number.longValue()));
    }
    Object status = args.get("status");
    if (status instanceof Number number) {
      dto.setStatus(List.of(number.intValue()));
    }
    dto.setPageNum(toInt(args.get("pageNum"), 1));
    dto.setPageSize(toInt(args.get("pageSize"), 10));

    Page<UserVO> page = userService.findPage(dto);
    List<UserVO> records = page.getRecords();
    RoleAO currentUser = BaseUnit.getCurrentRole();
    return trimPageResult(filterQueryUserByDept(records, currentUser));
  }

  /**
   * 部门隔离：非豁免操作者仅保留与当前操作者同部门（deptId）的用户记录；豁免角色不过滤保持全量。 隔离在 AI 工具内完成，不扩展共享查询 DTO， 以保持 {@code
   * /user/find} 等既有接口的 OpenAPI 契约零漂移（spec/ 零 diff）。
   *
   * @param records findPage 返回的用户记录
   * @param currentUser 当前操作用户（可能为空）
   * @return 过滤后的用户记录
   */
  private List<UserVO> filterQueryUserByDept(List<UserVO> records, RoleAO currentUser) {
    List<UserVO> result = records;
    if (!isQueryUserDeptExempt(currentUser)) {
      Long currentDeptId = currentUser == null ? null : currentUser.getDeptId();
      if (currentDeptId == null) {
        // 边界裁决：非豁免操作者无部门可见范围，返回空列表（对齐数据面 deptId 缺失→无可见范围语义），
        // 避免退化为全量泄露；操作者上下文缺失同判 fail-closed。
        result = java.util.List.of();
      } else if (records != null) {
        result =
            records.stream()
                .filter(user -> java.util.Objects.equals(user.getDeptId(), currentDeptId))
                .toList();
      }
    }
    return result;
  }

  /**
   * 部门隔离豁免判定：超管（roleId=1）保持全量可见。口径对齐既有豁免语义——{@link
   * com.slz.crm.server.interceptor.PermissionsInterceptor#checkPermission} 对 roleId=1 超管直通；{@link
   * com.slz.crm.server.service.impl.DataScopeLevelResolver} 对 roleId=1 授予 ALL 数据范围。
   */
  private boolean isQueryUserDeptExempt(RoleAO currentUser) {
    return currentUser != null
        && currentUser.getRoleId() != null
        && currentUser.getRoleId().longValue() == 1L;
  }

  /** 查询系统组织架构部门列表（可选含停用）。 */
  public Object executeQueryDept(Map<String, Object> args, ToolContext toolContext) {
    boolean includeDisabled = Boolean.TRUE.equals(args.get("includeDisabled"));
    List<SysDeptEntity> depts =
        includeDisabled ? sysDeptService.listAll() : sysDeptService.listEnabled();
    return trimPageResult(depts);
  }

  /** 按客户公司ID查询客户公司档案详情。 */
  public Object executeGetCustomerCompanyDetail(Map<String, Object> args, ToolContext toolContext) {
    Long companyId = toLong(args.get("companyId"));
    if (companyId == null) {
      throw new IllegalArgumentException("companyId 不能为空");
    }
    return customerCompanyService.getCompanyDetail(companyId);
  }

  /** 查询合同与商机统计报表（reportType 必填）。 */
  public Object executeQueryReport(Map<String, Object> args, ToolContext toolContext) {
    String reportType = (String) args.get("reportType");
    if (reportType == null || reportType.isBlank()) {
      throw new IllegalArgumentException("reportType 不能为空，取值 CONTRACT / BUSINESS / OPPORTUNITY");
    }
    LocalDateTime startTime = parseTime(args.get("startTime"));
    LocalDateTime endTime = parseTime(args.get("endTime"));
    Object report;
    if ("CONTRACT".equalsIgnoreCase(reportType)) {
      report = reportService.getTotalSignContractNum(startTime, endTime);
    } else if ("BUSINESS".equalsIgnoreCase(reportType)
        || "OPPORTUNITY".equalsIgnoreCase(reportType)) {
      report = reportService.getTotalBusinessNum(startTime, endTime);
    } else {
      throw new IllegalArgumentException(
          "不支持的 reportType: " + reportType + "，取值 CONTRACT / BUSINESS / OPPORTUNITY");
    }
    return report;
  }

  /** 将商机分页结果转换为精简行记录。 */
  public static List<Map<String, Object>> toOpportunityRows(List<SalesOpportunityVO> records) {
    List<Map<String, Object>> result = new ArrayList<>();
    for (SalesOpportunityVO vo : records) {
      Map<String, Object> item = new LinkedHashMap<>();
      item.put("id", vo.getId());
      item.put("opportunityName", vo.getOpportunityName());
      item.put("companyName", vo.getCompanyName());
      item.put("stage", vo.getStage());
      item.put("amount", vo.getAmount());
      result.add(item);
    }
    return result;
  }

  /** 将集团主数据列表转换为精简行记录。 */
  public static List<Map<String, Object>> toCompanyGroupRows(List<CompanyGroupEntity> groups) {
    List<Map<String, Object>> result = new ArrayList<>();
    for (CompanyGroupEntity group : groups) {
      Map<String, Object> item = new LinkedHashMap<>();
      item.put("id", group.getId());
      item.put("groupName", group.getGroupName());
      item.put("status", group.getStatus());
      result.add(item);
    }
    return result;
  }

  private LocalDateTime parseTime(Object value) {
    return value == null ? null : LocalDateTime.parse((String) value, DATE_TIME_FORMAT);
  }

  private <T> List<T> trimPageResult(List<T> records) {
    List<T> result = records;
    if (records != null && records.size() > 10) {
      result = records.subList(0, 10);
    }
    return result;
  }

  private Integer toInt(Object value, int defaultValue) {
    return value instanceof Number number ? number.intValue() : defaultValue;
  }

  private Long toLong(Object value) {
    return value instanceof Number number ? number.longValue() : null;
  }
}
