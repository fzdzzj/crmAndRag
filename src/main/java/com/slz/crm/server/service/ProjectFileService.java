package com.slz.crm.server.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.slz.crm.pojo.dto.ProjectFileDTO;
import com.slz.crm.pojo.dto.ProjectFileQueryDTO;
import com.slz.crm.pojo.vo.ProjectFileVO;

import java.util.List;

/**
 * 项目文件服务接口
 */
public interface ProjectFileService {

    /**
     * 基于业务活动上传文件
     * @param activityId 业务活动ID
     * @param dtoList 文件DTO列表
     */
    void uploadByActivity(Long activityId, List<ProjectFileDTO> dtoList);

    /**
     * 关联订单上传文件
     * @param orderId 订单项ID
     * @param dtoList 文件DTO列表
     */
    void uploadByOrder(Long orderId, List<ProjectFileDTO> dtoList);

    /**
     * 单独上传文件
     * @param dtoList 文件DTO列表
     */
    void uploadStandalone(List<ProjectFileDTO> dtoList);

    /**
     * 分页查询文件列表
     * @param pageNum 页码
     * @param pageSize 每页数量
     * @param queryDTO 查询条件
     * @return 分页结果
     */
    Page<ProjectFileVO> queryPage(Integer pageNum, Integer pageSize, ProjectFileQueryDTO queryDTO);

    /**
     * 查看业务活动下的文件列表
     * @param activityId 业务活动ID
     * @return 文件列表
     */
    List<ProjectFileVO> listByActivityId(Long activityId);

    /**
     * 查看订单项下的文件列表
     * @param orderId 订单项ID
     * @return 文件列表
     */
    List<ProjectFileVO> listByOrderId(Long orderId);

    /**
     * 查看合同下的文件列表(聚合所有订单项文件)
     * @param contractId 合同ID
     * @return 文件列表
     */
    List<ProjectFileVO> listByContractId(Long contractId);

    /**
     * 查看销售机会下的文件列表
     * @param opportunityId 销售机会ID
     * @return 文件列表
     */
    List<ProjectFileVO> listByOpportunityId(Long opportunityId);

    /**
     * 删除文件
     * @param ids 文件ID列表
     */
    void deleteByIds(List<Long> ids);
}
