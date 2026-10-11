package com.slz.crm.server.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.slz.crm.common.annotation.RequirePermission;
import com.slz.crm.common.enumeration.PermissionOperates;
import com.slz.crm.common.result.Result;
import com.slz.crm.pojo.dto.PromptTemplateDTO;
import com.slz.crm.pojo.dto.PromptTemplateRenderDTO;
import com.slz.crm.pojo.vo.PromptTemplateHistoryVO;
import com.slz.crm.pojo.vo.PromptTemplateRenderVO;
import com.slz.crm.pojo.vo.PromptTemplateVO;
import com.slz.crm.server.service.PromptTemplateService;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 提示词模板管理 REST 接口（add-prompt-template-admin 任务 2.3）。
 *
 * <p>路由前缀 {@code /promptTemplate}，8 端点两档分权：VIEW（808）——分页/详情/历史/渲染；MANAGE（809）——
 * 创建/更新/删除/启停用。所有端点挂方法级 {@code @RequirePermission}，零裸奔零 INTENTIONAL_OPEN。 渲染为纯 Java 占位符组装，零真实模型外呼。
 *
 * <p>参照 AssistController 模式：{@code Result} 统一包装，MyBatis-Plus {@code Page} 分页。
 */
@RestController
@RequestMapping("/promptTemplate")
public class PromptTemplateController {

  @Autowired private PromptTemplateService promptTemplateService;

  /** 分页查询模板（code/name/category/status 过滤）。 */
  @GetMapping("/page")
  @RequirePermission(PermissionOperates.PROMPT_TEMPLATE_VIEW)
  public Result<Page<PromptTemplateVO>> page(
      @RequestParam(defaultValue = "1") Integer pageNum,
      @RequestParam(defaultValue = "10") Integer pageSize,
      @RequestParam(required = false) String code,
      @RequestParam(required = false) String name,
      @RequestParam(required = false) String category,
      @RequestParam(required = false) Integer status) {
    return Result.success(
        promptTemplateService.page(pageNum, pageSize, code, name, category, status));
  }

  /** 查询模板详情（含正文与变量定义）。 */
  @GetMapping("/{id}")
  @RequirePermission(PermissionOperates.PROMPT_TEMPLATE_VIEW)
  public Result<PromptTemplateVO> detail(@PathVariable Long id) {
    return Result.success(promptTemplateService.detail(id));
  }

  /** 创建模板（version=1，历史落 CREATE）。 */
  @PostMapping("/create")
  @RequirePermission(PermissionOperates.PROMPT_TEMPLATE_MANAGE)
  public Result<Long> create(@RequestBody PromptTemplateDTO dto) {
    return Result.success(promptTemplateService.create(dto));
  }

  /** 更新模板（乐观锁 expectedVersion + version 递增 + 历史落 UPDATE）。 */
  @PostMapping("/update")
  @RequirePermission(PermissionOperates.PROMPT_TEMPLATE_MANAGE)
  public Result<Boolean> update(@RequestBody PromptTemplateDTO dto) {
    return Result.success(promptTemplateService.update(dto));
  }

  /** 删除模板（同事务先删 history 再删主表）。 */
  @DeleteMapping("/{id}")
  @RequirePermission(PermissionOperates.PROMPT_TEMPLATE_MANAGE)
  public Result<Boolean> delete(@PathVariable Long id) {
    return Result.success(promptTemplateService.delete(id));
  }

  /** 启停用模板（不改 version，历史落 STATUS）。 */
  @PostMapping("/status")
  @RequirePermission(PermissionOperates.PROMPT_TEMPLATE_MANAGE)
  public Result<Boolean> status(@RequestParam Long id, @RequestParam Integer status) {
    return Result.success(promptTemplateService.changeStatus(id, status));
  }

  /** 查询模板版本历史（按版本倒序）。 */
  @GetMapping("/{id}/history")
  @RequirePermission(PermissionOperates.PROMPT_TEMPLATE_VIEW)
  public Result<List<PromptTemplateHistoryVO>> history(@PathVariable Long id) {
    return Result.success(promptTemplateService.history(id));
  }

  /** 渲染模板：启用态模板 + required 校验 + 未定义变量拒绝 + {{var}} 占位符替换，返回组装文本与版本。 */
  @PostMapping("/render")
  @RequirePermission(PermissionOperates.PROMPT_TEMPLATE_VIEW)
  public Result<PromptTemplateRenderVO> render(@RequestBody PromptTemplateRenderDTO dto) {
    return Result.success(promptTemplateService.render(dto));
  }
}
