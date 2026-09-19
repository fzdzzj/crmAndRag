package com.slz.crm.server.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.slz.crm.pojo.entity.SalesStageApprovalEntity;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface SalesStageApprovalMapper extends BaseMapper<SalesStageApprovalEntity> {

  /**
   * 根据商机ID查询已通过的审批记录（用于构建阶段时间线）
   *
   * @param opportunityId 商机ID
   * @return 已通过的审批记录列表,按审批时间升序排列
   */
  @Select(
      "SELECT * FROM sales_stage_approval "
          + "WHERE opportunity_id = #{opportunityId} "
          + "AND approval_status = 1 "
          + "ORDER BY approval_time ASC")
  List<SalesStageApprovalEntity> selectApprovedApprovalsByOpportunityId(
      @Param("opportunityId") Long opportunityId);

  /**
   * 根据商机ID查询所有审批记录（用于构建审批记录列表）
   *
   * @param opportunityId 商机ID
   * @return 所有审批记录列表,按申请时间升序排列
   */
  @Select(
      "SELECT * FROM sales_stage_approval "
          + "WHERE opportunity_id = #{opportunityId} "
          + "ORDER BY apply_time ASC")
  List<SalesStageApprovalEntity> selectAllApprovalsByOpportunityId(
      @Param("opportunityId") Long opportunityId);
}
