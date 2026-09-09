package com.slz.crm.server.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.slz.crm.common.annotation.RequirePermission;
import com.slz.crm.common.enumeration.PermissionOperates;
import com.slz.crm.common.result.Result;
import com.slz.crm.pojo.dto.TaskCommentDTO;
import com.slz.crm.pojo.vo.TaskCommentVO;
import com.slz.crm.server.service.TaskCommentService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 任务评论控制器
 */
@RestController
@RequestMapping("/taskComment")
@Slf4j
public class TaskCommentController {

    @Autowired
    private TaskCommentService taskCommentService;

    /**
     * 创建任务评论
     */
    @PostMapping("/create")
    @RequirePermission(PermissionOperates.TASK_CREATE_TASK)
    public Result<Boolean> create(@RequestBody TaskCommentDTO taskCommentDTO) {
        Boolean result = taskCommentService.create(taskCommentDTO);
        return Result.success(result);
    }

    /**
     * 批量删除任务评论
     */
    @DeleteMapping("/batch")
    @RequirePermission(PermissionOperates.TASK_DELETE_TASK)
    public Result<Integer> deleteByIds(@RequestBody List<Long> idList) {
        Integer count = taskCommentService.deleteByIds(idList);
        return Result.success(count);
    }

    /**
     * 删除单个任务评论
     */
    @DeleteMapping("/{id}")
    @RequirePermission(PermissionOperates.TASK_DELETE_TASK)
    public Result<Boolean> deleteById(@PathVariable Long id) {
        Boolean result = taskCommentService.deleteById(id);
        return Result.success(result);
    }

    /**
     * 更新任务评论
     */
    @PutMapping("/update")
    @RequirePermission(PermissionOperates.TASK_UPDATE_TASK)
    public Result<Boolean> update(@RequestBody TaskCommentDTO taskCommentDTO) {
        Boolean result = taskCommentService.update(taskCommentDTO);
        return Result.success(result);
    }

    /**
     * 根据ID查询任务评论详情
     */
    @GetMapping("/{id}")
    @RequirePermission(PermissionOperates.TASK_VIEW_TASK)
    public Result<TaskCommentVO> getById(@PathVariable Long id) {
        TaskCommentVO taskCommentVO = taskCommentService.getById(id);
        return Result.success(taskCommentVO);
    }

    /**
     * 分页查询所有任务评论
     */
    @GetMapping("/all")
    @RequirePermission(PermissionOperates.TASK_VIEW_TASK)
    public Result<Page<TaskCommentVO>> getAll(
            @RequestParam(defaultValue = "1") Integer pageNum,
            @RequestParam(defaultValue = "10") Integer pageSize) {
        Page<TaskCommentVO> page = taskCommentService.getAll(pageNum, pageSize);
        return Result.success(page);
    }

    /**
     * 根据任务ID查询评论列表
     */
    @GetMapping("/task/{taskId}")
    @RequirePermission(PermissionOperates.TASK_VIEW_TASK)
    public Result<List<TaskCommentVO>> getByTaskId(@PathVariable Long taskId) {
        List<TaskCommentVO> comments = taskCommentService.getByTaskId(taskId);
        return Result.success(comments);
    }

    /**
     * 根据任务ID分页查询评论
     */
    @GetMapping("/task/{taskId}/page")
    @RequirePermission(PermissionOperates.TASK_VIEW_TASK)
    public Result<Page<TaskCommentVO>> getByTaskIdPage(
            @PathVariable Long taskId,
            @RequestParam(defaultValue = "1") Integer pageNum,
            @RequestParam(defaultValue = "10") Integer pageSize) {
        Page<TaskCommentVO> page = taskCommentService.getByTaskId(taskId, pageNum, pageSize);
        return Result.success(page);
    }
}