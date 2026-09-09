package com.slz.crm.server.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.slz.crm.pojo.dto.TaskCommentDTO;
import com.slz.crm.pojo.vo.TaskCommentVO;

import java.util.List;

/**
 * 任务评论服务接口
 */
public interface TaskCommentService {

    /**
     * 创建任务评论
     * @param taskCommentDTO 评论DTO
     * @return 创建结果
     */
    Boolean create(TaskCommentDTO taskCommentDTO);

    /**
     * 根据ID删除评论
     * @param id 评论ID
     * @return 删除结果
     */
    Boolean deleteById(Long id);

    /**
     * 批量删除评论
     * @param idList 评论ID列表
     * @return 删除数量
     */
    Integer deleteByIds(List<Long> idList);

    /**
     * 更新评论
     * @param taskCommentDTO 评论DTO
     * @return 更新结果
     */
    Boolean update(TaskCommentDTO taskCommentDTO);

    /**
     * 根据ID查询评论
     * @param id 评论ID
     * @return 评论VO
     */
    TaskCommentVO getById(Long id);

    /**
     * 分页查询所有评论
     * @param pageNum 页码
     * @param pageSize 页大小
     * @return 分页结果
     */
    Page<TaskCommentVO> getAll(Integer pageNum, Integer pageSize);

    /**
     * 根据任务ID查询评论列表
     * @param taskId 任务ID
     * @return 评论列表
     */
    List<TaskCommentVO> getByTaskId(Long taskId);

    /**
     * 根据任务ID分页查询评论
     * @param taskId 任务ID
     * @param pageNum 页码
     * @param pageSize 页大小
     * @return 分页结果
     */
    Page<TaskCommentVO> getByTaskId(Long taskId, Integer pageNum, Integer pageSize);
}