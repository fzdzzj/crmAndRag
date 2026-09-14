package com.slz.crm.server.controller;

import com.slz.crm.common.result.Result;
import com.slz.crm.common.annotation.RequirePermission;
import com.slz.crm.common.enumeration.PermissionOperates;
import com.slz.crm.common.untils.BaseUnit;
import com.slz.crm.pojo.vo.AiConfirmResultVO;
import com.slz.crm.pojo.vo.AiPendingActionVO;
import com.slz.crm.server.service.PendingActionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * AI 待确认操作接口（confirm/cancel/edit/查询）
 */
@RestController
@RequestMapping("/ai/actions")
@Slf4j

public class AiActionController {


    @Autowired
    private PendingActionService pendingActionService;

    /**
     * 确认执行待确认操作（返回执行结果 + 新创建实体引用，供卡片渲染跳转标签）
     * 权限按 action_type 运行时映射：
     *   CREATE_ORDER → SALES_APPEND_ORDER
     *   CREATE_CONTRACT → SALES_CREATE_CONTRACT
     */
    @PostMapping("/{pendingId}/confirm")
    // apply-permission-matrix 任务 1.2：AI 模块确认执行待确认操作
    @RequirePermission(PermissionOperates.AI_ACTION_CONFIRM)
    public Result<AiConfirmResultVO> confirm(@PathVariable String pendingId) {
        Long userId = BaseUnit.getCurrentId();

        AiConfirmResultVO result = pendingActionService.confirm(pendingId, userId);

        return Result.success(result);

    }

    /**
     * 取消待确认操作（无需写权限，仅校验归属）
     */
    @PostMapping("/{pendingId}/cancel")
    // apply-permission-matrix 任务 1.2：AI 模块确认执行待确认操作（取消）
    @RequirePermission(PermissionOperates.AI_ACTION_CONFIRM)
    public Result<Boolean> cancel(@PathVariable String pendingId) {
        Long userId = BaseUnit.getCurrentId();

        pendingActionService.cancel(pendingId, userId);

        return Result.success(true);

    }

    /**
     * 编辑草稿参数（重新校验，expire_time 重算）
     * 权限按 action_type 运行时映射（与 confirm 一致）
     */
    @PutMapping("/{pendingId}/edit")
    // apply-permission-matrix 任务 1.2：AI 模块确认执行待确认操作（编辑草稿）
    @RequirePermission(PermissionOperates.AI_ACTION_CONFIRM)
    public Result<AiPendingActionVO> edit(@PathVariable String pendingId,
                                          @RequestBody Map<String, Object> body) {
        Long userId = BaseUnit.getCurrentId();

        String newPayload = body.get("payload") != null ? body.get("payload").toString() : null;

        AiPendingActionVO vo = pendingActionService.edit(pendingId, userId, newPayload);

        return Result.success(vo);

    }

    /**
     * 查询待确认操作当前状态
     */
    @GetMapping("/{pendingId}")
    // apply-permission-matrix 任务 1.2：AI 模块查看待确认操作
    @RequirePermission(PermissionOperates.AI_ACTION_VIEW)
    public Result<AiPendingActionVO> getStatus(@PathVariable String pendingId) {
        AiPendingActionVO vo = pendingActionService.getStatus(pendingId, BaseUnit.getCurrentId());

        if (vo == null) {

            return Result.error("操作不存在或无权查看");

        }
        return Result.success(vo);

    }
}
