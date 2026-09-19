package com.slz.crm.platform.config.controller;

import com.slz.crm.common.result.Result;
import com.slz.crm.platform.config.ConfigHistoryView;
import com.slz.crm.platform.config.ConfigItemView;
import com.slz.crm.platform.config.service.DynamicConfigAdminService;
import java.util.List;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 超级管理员动态配置中心 REST 接口（任务 16）。
 *
 * <p>权限：本控制器所有端点仅超级管理员（roleId=1）可访问——越权由 {@link DynamicConfigAdminService} 统一判定并抛
 * FORBIDDEN(96005)（未登录 UNAUTHORIZED(96003)）， 由既有 {@code GlobalExceptionHandler} 封装为统一 {@link
 * Result} 返回。
 *
 * <p>约定：{@code value} 字段对敏感键返回掩码（{@code ******}）；写路径校验失败返回 VALIDATION(96007) 且不产生任何变更。响应统一 {@link
 * Result}（code=1 成功）。
 *
 * <p>路由前缀 {@code /platform/config} 位于 JWTInterceptor（/**）覆盖范围，登录态由认证层保证。
 */
@RestController
@RequestMapping("/platform/config")
public class DynamicConfigAdminController {

  private final DynamicConfigAdminService adminService;

  public DynamicConfigAdminController(DynamicConfigAdminService adminService) {
    this.adminService = adminService;
  }

  /**
   * 按命名空间列出全部配置项（含静态默认展示）。
   *
   * @param namespace 命名空间（ai.prompt/ai.model/rag.retrieval/rag.intent/business）；空 = 全部
   */
  @GetMapping("/items")
  public Result<List<ConfigItemView>> list(@RequestParam(required = false) String namespace) {
    return Result.success(adminService.listItems(namespace));
  }

  /** 查询单个配置项详情（敏感值掩码） */
  @GetMapping("/items/{key}")
  public Result<ConfigItemView> detail(@PathVariable String key) {
    return Result.success(adminService.getItem(key));
  }

  /** 查询配置键的版本历史（按版本倒序；旧/新值对敏感键掩码） */
  @GetMapping("/items/{key}/history")
  public Result<List<ConfigHistoryView>> history(@PathVariable String key) {
    return Result.success(adminService.history(key));
  }

  /** 更新（或首次创建/复活）配置项：写前过校验护栏，非法值拒绝并保持原值 */
  @PostMapping("/items")
  public Result<ConfigItemView> update(@RequestBody ConfigUpdateRequest request) {
    return Result.success(
        adminService.updateValue(request.getKey(), request.getValue(), request.getRemark()));
  }

  /** 回滚到指定历史版本（回滚本身也留痕并热生效） */
  @PostMapping("/items/{key}/rollback")
  public Result<ConfigItemView> rollback(
      @PathVariable String key, @RequestBody ConfigRollbackRequest request) {
    Integer targetVersion = request.getVersion();
    if (targetVersion == null || targetVersion <= 0) {
      return Result.error(
          com.slz.crm.platform.contract.PlatformErrorCode.VALIDATION.getCode(), "回滚目标版本号必须为正整数");
    }
    return Result.success(adminService.rollback(key, targetVersion, request.getRemark()));
  }

  /** 软删除配置项 = 恢复静态默认（历史保留可回滚/复活） */
  @DeleteMapping("/items/{key}")
  public Result<ConfigItemView> delete(
      @PathVariable String key, @RequestParam(required = false) String remark) {
    return Result.success(adminService.deleteOverride(key, remark));
  }

  /** 手动触发缓存全量刷新（多实例兜底手段），返回刷新后条目数 */
  @PostMapping("/cache/refresh")
  public Result<Integer> refreshCache() {
    return Result.success(adminService.refreshCache());
  }
}
