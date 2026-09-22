package com.slz.crm.server.init;

import com.slz.crm.common.enumeration.PermissionOperates;
import com.slz.crm.pojo.entity.PermissionsEntity;
import com.slz.crm.server.mapper.PermissionsMapper;
import jakarta.annotation.Resource;
import java.util.*;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 权限同步启动器 在 Spring Boot 启动时自动同步权限数据
 *
 * <p>可以通过配置控制是否启用： - application.yml: permission.sync.enabled=true - 启动参数:
 * --permission.sync.enabled=true - 环境变量: PERMISSION_SYNC_ENABLED=true
 *
 * <p>执行顺序：使用 @Order(1) 确保在其他初始化任务之前执行
 */
@Component
@Slf4j
@Order(1)
@ConditionalOnProperty(
    prefix = "permission.sync",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = true // 默认启用
    )
public class PermissionSyncRunner {

  @Value("${permission.sync.enabled:true}")
  private boolean permissionSyncEnabled;

  @Resource private PermissionsMapper permissionsMapper;

  @Transactional(rollbackFor = Exception.class)
  public void start() {
    if (!permissionSyncEnabled) {
      log.info("========================================");
      log.info("权限同步已禁用，跳过执行");
      log.info("========================================");
      return;
    }

    log.info("========================================");
    log.info("开始权限同步...");
    log.info("配置状态: {}", permissionSyncEnabled ? "启用" : "禁用");
    log.info("========================================");

    // 1. 获取数据库中所有现有权限
    List<PermissionsEntity> existingPermissions = permissionsMapper.selectList(null);
    Map<Long, PermissionsEntity> existingPermissionMap =
        existingPermissions.stream()
            .collect(Collectors.toMap(PermissionsEntity::getId, perm -> perm));

    log.info("数据库中现有权限数量: {}", existingPermissionMap.size());

    // 2. 构建所有需要的权限（按模块分组）
    Map<String, List<PermissionsEntity>> permissionsByModule = buildAllRequiredPermissions();

    // 3. 打印权限分组信息
    printPermissionGroupInfo(permissionsByModule);

    // 4. 批量处理：新增、更新、删除
    processPermissions(permissionsByModule, existingPermissionMap);

    int totalPermissions = permissionsByModule.values().stream().mapToInt(List::size).sum();
    log.info("========================================");
    log.info("权限同步完成，共处理 {} 个权限", totalPermissions);
    log.info("========================================");
  }

  /**
   * 构建所有需要的权限实体，按模块分组
   *
   * @return 按模块分组的权限Map
   */
  private Map<String, List<PermissionsEntity>> buildAllRequiredPermissions() {
    Map<String, List<PermissionsEntity>> permissionsByModule = new LinkedHashMap<>();

    // 初始化所有模块
    permissionsByModule.put("客户管理模块", new ArrayList<>());
    permissionsByModule.put("销售管理模块", new ArrayList<>());
    permissionsByModule.put("财务管理模块", new ArrayList<>());
    permissionsByModule.put("联络任务模块", new ArrayList<>());
    permissionsByModule.put("统计报表模块", new ArrayList<>());
    permissionsByModule.put("权限管理模块", new ArrayList<>());
    permissionsByModule.put("隐私信息模块", new ArrayList<>());

    // 遍历所有权限枚举值并按模块分组
    for (PermissionOperates operation : PermissionOperates.values()) {
      PermissionsEntity perm = new PermissionsEntity();
      perm.setId(operation.getId());

      // 根据枚举常量名称确定模块分组；权限名用枚举裸名，
      // 与 V21/V26/V27 迁移种子（permissions_name varchar(50)，最长裸名 46）完全一致
      String module = getModuleByEnumName(operation.name());
      perm.setPermissionsName(operation.name());
      perm.setPermissionsDesc(operation.getDescription());

      // 未知前缀（如后加的 AI_/KNOWLEDGE_）归入「其他模块」；computeIfAbsent 保证新增前缀不再 NPE
      permissionsByModule.computeIfAbsent(module, key -> new ArrayList<>()).add(perm);
    }

    return permissionsByModule;
  }

  /**
   * 根据枚举常量名称获取所属模块
   *
   * @param enumName 枚举常量名称
   * @return 模块名称
   */
  private String getModuleByEnumName(String enumName) {
    final String result;
    if (enumName.startsWith("CUSTOMER_")) {
      result = "客户管理模块";
    } else if (enumName.startsWith("SALES_")) {
      result = "销售管理模块";
    } else if (enumName.startsWith("FINANCE_")) {
      result = "财务管理模块";
    } else if (enumName.startsWith("TASK_")) {
      result = "联络任务模块";
    } else if (enumName.startsWith("REPORT_")) {
      result = "统计报表模块";
    } else if (enumName.startsWith("SYSTEM_")) {
      result = "权限管理模块";
    } else if (enumName.startsWith("PRIVACY_")) {
      result = "隐私信息模块";
    } else {
      result = "其他模块";
    }
    return result;
  }

  /** 打印权限分组信息 */
  private void printPermissionGroupInfo(Map<String, List<PermissionsEntity>> permissionsByModule) {
    log.info("权限分组详情:");
    permissionsByModule.forEach(
        (module, permissions) -> {
          log.info("  - {}: {} 个权限", module, permissions.size());
          // 打印该模块的所有权限ID和名称
          permissions.forEach(
              perm ->
                  log.info(
                      "    [{}] {} - {}",
                      perm.getId(),
                      perm.getPermissionsName(),
                      perm.getPermissionsDesc()));
        });
  }

  /** 批量处理权限：新增、更新、删除 按模块分组处理，便于问题排查 */
  @SuppressWarnings("PMD.AvoidCatchingGenericException") // 批量插入ORM多源，失败按模块统计继续，不中断整体同步
  private void processPermissions(
      Map<String, List<PermissionsEntity>> permissionsByModule,
      Map<Long, PermissionsEntity> existingPermissionMap) {

    List<PermissionsEntity> toInsert = new ArrayList<>();
    List<PermissionsEntity> toUpdate = new ArrayList<>();
    Map<String, int[]> insertStats = new HashMap<>();
    Map<String, int[]> updateStats = new HashMap<>();

    // 初始化统计
    permissionsByModule
        .keySet()
        .forEach(
            module -> {
              insertStats.put(module, new int[] {0});
              updateStats.put(module, new int[] {0});
            });

    // 1. 按模块处理权限
    collectPermissionChanges(
        permissionsByModule, existingPermissionMap, toInsert, toUpdate, insertStats, updateStats);

    // 2. 顺序插入权限
    insertPermissions(toInsert, insertStats);

    // 3. 批量更新权限
    updatePermissions(toUpdate, updateStats);
  }

  /** 按模块比对声明权限与库内存量：新权限进新增列表，描述/名称有变的进更新列表，并累计模块统计 */
  private void collectPermissionChanges(
      Map<String, List<PermissionsEntity>> permissionsByModule,
      Map<Long, PermissionsEntity> existingPermissionMap,
      List<PermissionsEntity> toInsert,
      List<PermissionsEntity> toUpdate,
      Map<String, int[]> insertStats,
      Map<String, int[]> updateStats) {
    for (Map.Entry<String, List<PermissionsEntity>> entry : permissionsByModule.entrySet()) {
      String module = entry.getKey();
      List<PermissionsEntity> modulePermissions = entry.getValue();

      for (PermissionsEntity requiredPerm : modulePermissions) {
        PermissionsEntity existingPerm = existingPermissionMap.get(requiredPerm.getId());

        if (existingPerm == null) {
          // 新权限：添加到新增列表
          toInsert.add(requiredPerm);
          insertStats.get(module)[0]++;
          log.debug(
              "[{}] 新增权限: {} - {}",
              module,
              requiredPerm.getPermissionsName(),
              requiredPerm.getPermissionsDesc());
        } else {
          // 已有权限：检查是否需要更新
          if (!Objects.equals(existingPerm.getPermissionsDesc(), requiredPerm.getPermissionsDesc())
              || !Objects.equals(
                  existingPerm.getPermissionsName(), requiredPerm.getPermissionsName())) {

            existingPerm.setPermissionsDesc(requiredPerm.getPermissionsDesc());
            existingPerm.setPermissionsName(requiredPerm.getPermissionsName());
            toUpdate.add(existingPerm);
            updateStats.get(module)[0]++;
            log.debug(
                "[{}] 更新权限: {} - {}",
                module,
                requiredPerm.getPermissionsName(),
                requiredPerm.getPermissionsDesc());
          }
        }
      }
    }
  }

  /** 顺序插入新权限（单条失败记日志继续），完成后打印按模块统计 */
  private void insertPermissions(List<PermissionsEntity> toInsert, Map<String, int[]> insertStats) {
    if (!toInsert.isEmpty()) {
      log.info("开始插入权限...");
      int successCount = 0;
      for (PermissionsEntity perm : toInsert) {
        try {
          permissionsMapper.insertANDID(perm);
          successCount++;
        } catch (Exception e) {
          log.error(
              "插入权限失败: {} - {} - 错误: {}",
              perm.getId(),
              perm.getPermissionsName(),
              e.getMessage(),
              e);
        }
      }

      // 打印插入统计
      log.info("权限插入完成，按模块统计:");
      insertStats.forEach(
          (module, stats) -> {
            if (stats[0] > 0) {
              log.info("  - {}: 新增 {} 个权限", module, stats[0]);
            }
          });
      log.info("总共成功插入 {} 个权限", successCount);
    }
  }

  /** 批量更新有变化的权限，完成后打印按模块统计 */
  private void updatePermissions(List<PermissionsEntity> toUpdate, Map<String, int[]> updateStats) {
    if (!toUpdate.isEmpty()) {
      log.info("开始批量更新权限...");
      toUpdate.forEach(permissionsMapper::updateById);

      // 打印更新统计
      log.info("权限更新完成，按模块统计:");
      updateStats.forEach(
          (module, stats) -> {
            if (stats[0] > 0) {
              log.info("  - {}: 更新 {} 个权限", module, stats[0]);
            }
          });
      log.info("总共更新权限: {} 个", toUpdate.size());
    }
  }
}
