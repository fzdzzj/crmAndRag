package com.slz.crm.platform.config.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.slz.crm.platform.config.CostKeyChangeRequestVO;
import com.slz.crm.platform.config.controller.CostKeyChangeRequestRejectReq;
import com.slz.crm.platform.config.controller.CostKeyChangeRequestSubmitReq;

/** 成本键申请-审批流服务（add-cost-key-approval-workflow 任务 2.1）。 */
public interface CostKeyChangeRequestService {

  /** 提交申请 */
  CostKeyChangeRequestVO submit(CostKeyChangeRequestSubmitReq req);

  /** 撤回申请（仅本人 PENDING 态） */
  CostKeyChangeRequestVO withdraw(Long id);

  /** 审批通过（仅超管） */
  CostKeyChangeRequestVO approve(Long id);

  /** 驳回申请（仅超管） */
  CostKeyChangeRequestVO reject(Long id, CostKeyChangeRequestRejectReq req);

  /** 清单分页查询（超管看全部、普通 608 只看自己） */
  Page<CostKeyChangeRequestVO> list(
      Integer pageNum, Integer pageSize, String status, String configKey);
}
