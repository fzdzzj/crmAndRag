package com.slz.crm.server.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.slz.crm.pojo.dto.LeadConvertDTO;
import com.slz.crm.pojo.dto.SalesLeadDTO;
import com.slz.crm.pojo.dto.SalesLeadQueryDTO;
import com.slz.crm.pojo.vo.LeadConvertVO;
import com.slz.crm.pojo.vo.SalesLeadVO;

/**
 * 销售线索服务（add-lead-management-and-conversion-funnel 任务 2.2）。
 *
 * <p>覆盖线索录入、跟进修改、无效关闭与分页查询，以及核心「一键转化」——单事务内生成客户公司、首要联系人及 可选销售机会，状态置为已转化并回填转化关联 ID，事务提交后异步联动语义向量同步。
 */
public interface SalesLeadService {

  /**
   * 录入新线索（状态默认 0 待跟进）。
   *
   * @param dto 线索数据
   * @return 新建线索 VO（携带新增 ID）
   */
  SalesLeadVO add(SalesLeadDTO dto);

  /**
   * 查看线索详情。
   *
   * @param id 线索 ID
   * @return 线索 VO
   */
  SalesLeadVO getDetail(Long id);

  /**
   * 更新线索资料或跟进状态。
   *
   * @param dto 线索数据（须携带 id）
   * @return 是否更新成功
   */
  boolean update(SalesLeadDTO dto);

  /**
   * 逻辑删除线索。
   *
   * @param id 线索 ID
   * @return 是否删除成功
   */
  boolean deleteById(Long id);

  /**
   * 无效关闭线索（状态置 3 已关闭无效）。
   *
   * @param id 线索 ID
   * @return 是否关闭成功
   */
  boolean closeInvalid(Long id);

  /**
   * 分页查询线索列表。
   *
   * @param query 查询条件
   * @param pageNum 页码
   * @param pageSize 每页数量
   * @return 线索分页
   */
  Page<SalesLeadVO> queryPage(SalesLeadQueryDTO query, Integer pageNum, Integer pageSize);

  /**
   * 一键转化销售线索为客户公司/首要联系人及可选销售机会。
   *
   * @param dto 转化入参（leadId 必填，createOpportunity 控制是否建商机）
   * @return 转化结果 VO（companyId / contactId / opportunityId）
   */
  LeadConvertVO convert(LeadConvertDTO dto);
}
