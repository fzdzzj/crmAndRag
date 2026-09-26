package com.slz.crm.server.service.impl;

import com.slz.crm.common.enumeration.ModelName;
import com.slz.crm.common.enumeration.PermissionOperates;
import com.slz.crm.pojo.entity.BusinessActivityEntity;
import com.slz.crm.pojo.entity.ContractEntity;
import com.slz.crm.pojo.entity.ContractOrderItemEntity;
import com.slz.crm.pojo.entity.ProjectFileEntity;
import com.slz.crm.pojo.entity.SalesOpportunityEntity;
import com.slz.crm.server.mapper.BusinessActivityMapper;
import com.slz.crm.server.mapper.BusinessActivityUserMapper;
import com.slz.crm.server.mapper.ContractMapper;
import com.slz.crm.server.mapper.ContractOrderItemMapper;
import com.slz.crm.server.mapper.SalesOpportunityMapper;
import com.slz.crm.server.service.PermissionService;
import java.util.Objects;

/**
 * 项目文件记录级读取判定协作类（tighten-pmd-residual-325 任务 6.4 批B：拆自 {@link AttachmentAccessServiceImpl}，行为等价）。
 *
 * <p>职责：项目文件多归属维度（活动/商机/合同/订单项/独立上传）的命中顺序判定， 以及被活动附件读取复用的活动记录级校验。用户状态/超管闸门与部门上司预留点仍归主服务， 预留点经
 * {@link ReservedRead} 回调注入，签名契约不挪窝。
 */
class ProjectFileAttachmentReader {

  /** 部门上司读取预留点回调：本体留在 AttachmentAccessServiceImpl（豁免与签名契约不迁移）。 */
  @FunctionalInterface
  interface ReservedRead {
    boolean reserved(String modelName, Long recordId, Long userId);
  }

  private final BusinessActivityMapper businessActivityMapper;
  private final BusinessActivityUserMapper businessActivityUserMapper;
  private final SalesOpportunityMapper salesOpportunityMapper;
  private final ContractMapper contractMapper;
  private final ContractOrderItemMapper contractOrderItemMapper;
  private final PermissionService permissionService;
  private final ReservedRead reservedRead;

  ProjectFileAttachmentReader(
      BusinessActivityMapper businessActivityMapper,
      BusinessActivityUserMapper businessActivityUserMapper,
      SalesOpportunityMapper salesOpportunityMapper,
      ContractMapper contractMapper,
      ContractOrderItemMapper contractOrderItemMapper,
      PermissionService permissionService,
      ReservedRead reservedRead) {
    this.businessActivityMapper = businessActivityMapper;
    this.businessActivityUserMapper = businessActivityUserMapper;
    this.salesOpportunityMapper = salesOpportunityMapper;
    this.contractMapper = contractMapper;
    this.contractOrderItemMapper = contractOrderItemMapper;
    this.permissionService = permissionService;
    this.reservedRead = reservedRead;
  }

  /**
   * 非超管的记录级判定：项目文件可能同时挂多个归属维度，任一维度可读即放行； 无任何归属维度的独立上传文件仅上传人本人可见；其余走部门主管保留通道。
   *
   * <p>update-project-file-list-auth-hotpath：{@code roleId} 来自调用方同一请求内已加载的用户实体， 维度权限判定复用该角色值， 不再按
   * userId 回查 {@code sys_user}；用户状态闸门与超管闸门仍由主服务在调用前实时校验。
   */
  boolean canReadByDimension(ProjectFileEntity file, Long userId, Long roleId) {
    boolean result = false;
    boolean hasDimension = file.getActivityId() != null || file.getOpportunityId() != null;
    if (file.getActivityId() != null) {
      result =
          canReadBusinessActivityAttachments(
              file.getActivityId(), userId, roleId, ModelName.BUSINESS_ACTIVITY);
    }
    if (!result && file.getOpportunityId() != null) {
      result = canReadSalesOpportunity(file.getOpportunityId(), userId, roleId);
    }
    if (!result) {
      Long contractId = resolveContractId(file);
      if (contractId != null && canReadContract(contractId, userId, roleId)) {
        result = true;
      } else if (!hasDimension && contractId == null) {
        // 无任何归属维度的独立上传文件：仅上传人本人可见（超管已在主服务放行）
        result = Objects.equals(file.getUploaderId(), userId);
      } else {
        result = reservedRead.reserved(ModelName.PROJECT_FILE, file.getId(), userId);
      }
    }
    return result;
  }

  /**
   * 活动附件记录级校验：模块查看权限 + 创建人/参与人，或部门主管预留通道；被主服务活动分支复用。
   *
   * <p>update-project-file-list-auth-hotpath：查看权限按调用方传入的 {@code roleId} 判定，省掉按 userId 回查用户解析角色。
   */
  boolean canReadBusinessActivityAttachments(
      Long recordId, Long userId, Long roleId, String modelName) {
    boolean hasViewPermission =
        permissionService.hasPermissionByRoleId(
            roleId, PermissionOperates.SALES_VIEW_BUSINESS_ACTIVITY);
    BusinessActivityEntity activity =
        hasViewPermission ? businessActivityMapper.selectById(recordId) : null;
    boolean recordVisible =
        activity != null
            && (Objects.equals(activity.getCreatorId(), userId)
                || businessActivityUserMapper.existsByActivityIdAndUserId(recordId, userId) > 0);
    return recordVisible || reservedRead.reserved(modelName, recordId, userId);
  }

  private boolean canReadSalesOpportunity(Long opportunityId, Long userId, Long roleId) {
    boolean hasViewPermission =
        permissionService.hasPermissionByRoleId(
            roleId, PermissionOperates.SALES_VIEW_SALE_OPPORTUNITY);
    SalesOpportunityEntity opportunity =
        hasViewPermission ? salesOpportunityMapper.selectById(opportunityId) : null;
    return opportunity != null
        && (Objects.equals(opportunity.getOwnerId(), userId)
            || Objects.equals(opportunity.getCreatorId(), userId)
            || Objects.equals(opportunity.getApproverId(), userId));
  }

  private boolean canReadContract(Long contractId, Long userId, Long roleId) {
    boolean hasViewPermission =
        permissionService.hasPermissionByRoleId(roleId, PermissionOperates.SALES_VIEW_CONTRACT);
    ContractEntity contract = hasViewPermission ? contractMapper.selectById(contractId) : null;
    return contract != null
        && (Objects.equals(contract.getOwnerId(), userId)
            || Objects.equals(contract.getCreatorId(), userId));
  }

  /** 订单维度文件经订单项反查合同，与合同归属共用同一授权判断。 */
  private Long resolveContractId(ProjectFileEntity file) {
    Long result = file.getContractId();
    if (file.getOrderId() != null) {
      ContractOrderItemEntity orderItem = contractOrderItemMapper.selectById(file.getOrderId());
      if (orderItem != null && orderItem.getContractId() != null) {
        result = orderItem.getContractId();
      }
    }
    return result;
  }
}
