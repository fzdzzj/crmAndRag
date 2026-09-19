package com.slz.crm.server.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.slz.crm.pojo.dto.SalesOpportunityDTO;
import com.slz.crm.pojo.dto.SalesOpportunityQueryDTO;
import com.slz.crm.pojo.vo.SalesOpportunityVO;
import java.util.List;

public interface SalesOpportunityService {
  /**
   * 创建销售机会
   *
   * @param dto 销售机会DTO
   * @return 销售机会VO
   */
  SalesOpportunityVO create(SalesOpportunityDTO dto);

  /**
   * 修改销售机会
   *
   * @param salesOpportunityDTO 销售机会DTO
   * @return 修改结果
   */
  boolean update(SalesOpportunityDTO salesOpportunityDTO);

  /**
   * 删除销售机会
   *
   * @param id 销售机会ID
   * @return 删除结果
   */
  boolean delete(Long id);

  /**
   * 获取所有销售机会
   *
   * @param pageNum 页码
   * @param pageSize 每页数量
   * @return 销售机会VO列表
   */
  Page<SalesOpportunityVO> getAllSalesOpportunity(Integer pageNum, Integer pageSize);

  /**
   * 自定义查询销售机会
   *
   * @param queryDTO 查询条件DTO
   * @param pageNum 页码
   * @param pageSize 每页数量
   * @return 销售机会VO列表
   */
  Page<SalesOpportunityVO> getSalesOpportunityByQuery(
      SalesOpportunityQueryDTO queryDTO, Integer pageNum, Integer pageSize);

  /**
   * 根据公司ID级联删除销售机会
   *
   * @param companyId 公司ID
   */
  void cascadeDeleteByCompanyId(Long companyId);

  /**
   * 根据联系人ID级联删除销售机会
   *
   * @param contactIds 联系人ID列表
   */
  void cascadeDeleteByContactIds(List<Long> contactIds);

  /**
   * 根据销售机会ID查询商机详情（包含商机信息、业务活动和状态变更记录）
   *
   * @param opportunityId 销售机会ID
   * @return 商机详情VO
   */
  com.slz.crm.pojo.vo.OpportunityDetailVO getOpportunityDetailById(Long opportunityId);

  /**
   * 根据合同ID查询商机详情（包含商机信息、业务活动和状态变更记录）
   *
   * @param contractId 合同ID
   * @return 商机详情VO
   */
  com.slz.crm.pojo.vo.OpportunityDetailVO getOpportunityDetailByContractId(Long contractId);
}
