package com.slz.crm.server.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.slz.crm.pojo.dto.ContactTaskDTO;
import com.slz.crm.pojo.vo.ContactTaskVO;

import java.util.List;

/**
 * 联络任务服务接口
 */
public interface ContactTaskService {

    /**
     * 创建联络任务
     * @param contactTaskDTO 联络任务数据
     * @return 是否创建成功
     */
    Boolean create(ContactTaskDTO contactTaskDTO);

    /**
     * 批量删除联络任务
     * @param idList 任务ID列表
     * @return 删除数量
     */
    Integer deleteByIds(List<Long> idList);

    /**
     * 根据ID删除单个联络任务
     * @param id 任务ID
     * @return 是否删除成功
     */
    Boolean deleteById(Long id);

    /**
     * 批量更新联络任务
     * @param contactTaskDTOList 联络任务数据列表
     * @return 更新数量
     */
    Integer updateList(List<ContactTaskDTO> contactTaskDTOList);

    /**
     * 更新单个联络任务
     * @param contactTaskDTO 联络任务数据
     * @return 是否更新成功
     */
    Boolean update(ContactTaskDTO contactTaskDTO);

    /**
     * 根据ID查询联络任务详情
     * @param id 任务ID
     * @return 联络任务详情
     */
    ContactTaskVO getById(Long id);

    /**
     * 分页查询所有联络任务
     * @param pageNum 页码
     * @param pageSize 每页数量
     * @return 分页联络任务列表
     */
    Page<ContactTaskVO> getAll(Integer pageNum, Integer pageSize);

    /**
     * 根据条件分页查询联络任务
     * @param pageNum 页码
     * @param pageSize 每页数量
     * @param contactTaskDTO 查询条件
     * @return 分页联络任务列表
     */
    Page<ContactTaskVO> query(Integer pageNum, Integer pageSize, ContactTaskDTO contactTaskDTO);

    /**
     * 根据执行人ID查询任务列表
     * @param assigneeId 执行人ID
     * @return 任务列表
     */
    List<ContactTaskVO> getByAssigneeId(Long assigneeId);

    /**
     * 根据公司ID查询任务列表
     * @param companyId 公司ID
     * @return 任务列表
     */
    List<ContactTaskVO> getByCompanyId(Long companyId);

    /**
     * 根据联系人ID查询任务列表
     * @param contactId 联系人ID
     * @return 任务列表
     */
    List<ContactTaskVO> getByContactId(Long contactId);

    /**
     * 根据销售机会ID查询任务列表
     * @param opportunityId 销售机会ID
     * @return 任务列表
     */
    List<ContactTaskVO> getByOpportunityId(Long opportunityId);
}