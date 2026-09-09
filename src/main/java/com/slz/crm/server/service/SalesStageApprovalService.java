package com.slz.crm.server.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.slz.crm.pojo.dto.SalesStageApprovalANDAttachmentDTO;
import com.slz.crm.pojo.dto.SalesStageApprovalDTO;
import com.slz.crm.pojo.dto.SalesStageApprovalPageDTO;
import com.slz.crm.pojo.entity.SalesStageApprovalEntity;
import com.slz.crm.pojo.vo.AddSalesStageApprovalDTO;
import com.slz.crm.pojo.vo.SalesOpportunityVO;
import com.slz.crm.pojo.vo.SalesStageApprovalVO;

import java.util.List;

public interface SalesStageApprovalService {
    /**
     * 申请推进销售机会阶段
     * @param dto 销售机会阶段审批DTO
     * @return 销售机会VO
     */
    SalesStageApprovalVO approveStage(AddSalesStageApprovalDTO dto);

    /** 保存阶段推进草稿，并向协助人创建协助申请。 */
    SalesStageApprovalVO saveDraft(AddSalesStageApprovalDTO dto);

    /** 查询当前申请人在指定销售机会下尚未提交审批的草稿。 */
    SalesStageApprovalVO getDraftByOpportunityId(Long opportunityId);

    /** 更新当前申请人的未提交阶段推进草稿。 */
    SalesStageApprovalVO updateDraft(Long id, AddSalesStageApprovalDTO dto);

    /** 将申请人保存的草稿提交为待审批。 */
    Boolean submitDraft(Long id);

    /**
     * 更新销售机会阶段审批
     * @param dto 销售机会阶段审批DTO
     * @return 更新是否成功
     */
    Boolean updateById(SalesStageApprovalDTO dto);

    /**
     * 删除销售机会阶段审批
     * @param ids 销售机会阶段审批ID列表
     */
    void removeByIds(List<Long> ids);

    /**
     * 获取销售机会阶段审批分页列表
     * @param page 分页对象
     * @param dto 销售机会阶段审批分页查询DTO
     * @return 销售机会阶段审批分页VO列表
     */
    Page<SalesStageApprovalVO> getPage(Page<SalesStageApprovalEntity> page, SalesStageApprovalDTO dto);
}
