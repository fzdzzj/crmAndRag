package com.slz.crm.server.service.impl;

import com.slz.crm.common.enumeration.ModelName;
import com.slz.crm.common.enumeration.PermissionOperates;
import com.slz.crm.pojo.entity.BusinessActivityEntity;
import com.slz.crm.pojo.entity.BusinessActivityUserEntity;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

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

  /** 非超管的记录级判定：项目文件可能同时挂多个归属维度，任一维度可读即放行； 无任何归属维度的独立上传文件仅上传人本人可见；其余走部门主管保留通道。 */
  boolean canReadByDimension(ProjectFileEntity file, Long userId) {
    boolean result = false;
    boolean hasDimension = file.getActivityId() != null || file.getOpportunityId() != null;
    if (file.getActivityId() != null) {
      result =
          canReadBusinessActivityAttachments(
              file.getActivityId(), userId, ModelName.BUSINESS_ACTIVITY);
    }
    if (!result && file.getOpportunityId() != null) {
      result = canReadSalesOpportunity(file.getOpportunityId(), userId);
    }
    if (!result) {
      Long contractId = resolveContractId(file);
      if (contractId != null && canReadContract(contractId, userId)) {
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

  /** 活动附件记录级校验：模块查看权限 + 创建人/参与人，或部门主管预留通道；被主服务活动分支复用。 */
  boolean canReadBusinessActivityAttachments(Long recordId, Long userId, String modelName) {
    boolean hasViewPermission =
        permissionService.hasPermission(userId, PermissionOperates.SALES_VIEW_BUSINESS_ACTIVITY);
    BusinessActivityEntity activity =
        hasViewPermission ? businessActivityMapper.selectById(recordId) : null;
    boolean recordVisible =
        activity != null
            && (Objects.equals(activity.getCreatorId(), userId)
                || businessActivityUserMapper.existsByActivityIdAndUserId(recordId, userId) > 0);
    return recordVisible || reservedRead.reserved(modelName, recordId, userId);
  }

  private boolean canReadSalesOpportunity(Long opportunityId, Long userId) {
    boolean hasViewPermission =
        permissionService.hasPermission(userId, PermissionOperates.SALES_VIEW_SALE_OPPORTUNITY);
    SalesOpportunityEntity opportunity =
        hasViewPermission ? salesOpportunityMapper.selectById(opportunityId) : null;
    return opportunity != null
        && (Objects.equals(opportunity.getOwnerId(), userId)
            || Objects.equals(opportunity.getCreatorId(), userId)
            || Objects.equals(opportunity.getApproverId(), userId));
  }

  private boolean canReadContract(Long contractId, Long userId) {
    boolean hasViewPermission =
        permissionService.hasPermission(userId, PermissionOperates.SALES_VIEW_CONTRACT);
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

  // ----------------------------------------------------------------
  // batch-project-file-list-auth-reads

  /**
   * batch-project-file-list-auth-reads 任务 3.2：列表链路批量维度判定（B1 保守批量化）。
   *
   * <p>先按 ID 去重预取维度数据——活动/商机/合同/订单项各至多 1 次 {@code selectBatchIds}、 活动参与人 1 次 IN 查询——再逐行套用与 {@link
   * #canReadByDimension} 逐字一致的判定矩阵。 {@code hasPermission} 保持逐行实时调用（次数不因批量化减少）；单行路径在维度权限 {@code
   * false} 时不查实体、批量路径无条件预取，预取数据不参与无权限行的判定（判定等价，仅多读）。
   *
   * @param files 待过滤行（调用方已完成状态/超管闸门）
   * @param userId 当前登录用户 ID
   * @return 按入参顺序排列的可读行子集
   */
  List<ProjectFileEntity> filterReadableByDimension(List<ProjectFileEntity> files, Long userId) {
    List<ProjectFileEntity> result = new ArrayList<>();
    if (files != null && !files.isEmpty()) {
      DimensionPrefetch prefetch = prefetchDimensionData(files, userId);
      for (ProjectFileEntity file : files) {
        if (file != null && file.getId() != null && isReadableByDimension(file, userId, prefetch)) {
          result.add(file);
        }
      }
    }
    return result;
  }

  /** 一次请求的维度预取快照：只含实体索引与当前用户的参与关系 ID，不含任何权限判定结果（权限链逐行实时）。 */
  private record DimensionPrefetch(
      Map<Long, BusinessActivityEntity> activities,
      Map<Long, SalesOpportunityEntity> opportunities,
      Map<Long, ContractOrderItemEntity> orderItems,
      Map<Long, ContractEntity> contracts,
      Set<Long> participantActivityIds) {}

  /** 按行集去重 ID 预取维度数据：活动/商机/订单项/合同各至多 1 次 {@code selectBatchIds} + 参与人 1 次 IN。 */
  private DimensionPrefetch prefetchDimensionData(List<ProjectFileEntity> files, Long userId) {
    Set<Long> activityIds = collectIds(files, ProjectFileEntity::getActivityId);
    Set<Long> opportunityIds = collectIds(files, ProjectFileEntity::getOpportunityId);
    Set<Long> orderIds = collectIds(files, ProjectFileEntity::getOrderId);
    Map<Long, BusinessActivityEntity> activities =
        activityIds.isEmpty()
            ? Map.of()
            : toEntityMap(
                businessActivityMapper.selectBatchIds(activityIds), BusinessActivityEntity::getId);
    Map<Long, SalesOpportunityEntity> opportunities =
        opportunityIds.isEmpty()
            ? Map.of()
            : toEntityMap(
                salesOpportunityMapper.selectBatchIds(opportunityIds),
                SalesOpportunityEntity::getId);
    Map<Long, ContractOrderItemEntity> orderItems =
        orderIds.isEmpty()
            ? Map.of()
            : toEntityMap(
                contractOrderItemMapper.selectBatchIds(orderIds), ContractOrderItemEntity::getId);
    // 生效合同 ID 按单行 resolveContractId 语义逐行求值（订单项命中则覆盖 file.contractId），去重后批量读合同
    Set<Long> effectiveContractIds = new LinkedHashSet<>();
    for (ProjectFileEntity file : files) {
      if (file != null && file.getId() != null) {
        Long contractId = effectiveContractId(file, orderItems);
        if (contractId != null) {
          effectiveContractIds.add(contractId);
        }
      }
    }
    Map<Long, ContractEntity> contracts =
        effectiveContractIds.isEmpty()
            ? Map.of()
            : toEntityMap(
                contractMapper.selectBatchIds(effectiveContractIds), ContractEntity::getId);
    return new DimensionPrefetch(
        activities,
        opportunities,
        orderItems,
        contracts,
        collectParticipantActivityIds(activityIds, userId));
  }

  /** 参与人 1 次 IN 查询：只保留当前用户的参与关系（与逐行 existsByActivityIdAndUserId 判定等价）。 */
  private Set<Long> collectParticipantActivityIds(Set<Long> activityIds, Long userId) {
    Set<Long> participantActivityIds = new LinkedHashSet<>();
    if (!activityIds.isEmpty()) {
      for (BusinessActivityUserEntity relation :
          businessActivityUserMapper.selectByActivityIds(new ArrayList<>(activityIds))) {
        if (relation != null
            && Objects.equals(relation.getUserId(), userId)
            && relation.getActivityId() != null) {
          participantActivityIds.add(relation.getActivityId());
        }
      }
    }
    return participantActivityIds;
  }

  /** 批量逐行判定矩阵：与 {@link #canReadByDimension} 顺序逐字一致，任一维度命中即读。 */
  private boolean isReadableByDimension(
      ProjectFileEntity file, Long userId, DimensionPrefetch prefetch) {
    boolean result = false;
    boolean hasDimension = file.getActivityId() != null || file.getOpportunityId() != null;
    if (file.getActivityId() != null) {
      result =
          judgeActivityBatch(
              file.getActivityId(),
              userId,
              ModelName.BUSINESS_ACTIVITY,
              prefetch.activities(),
              prefetch.participantActivityIds());
    }
    if (!result && file.getOpportunityId() != null) {
      result = judgeOpportunityBatch(file.getOpportunityId(), userId, prefetch.opportunities());
    }
    if (!result) {
      Long contractId = effectiveContractId(file, prefetch.orderItems());
      if (contractId != null && judgeContractBatch(contractId, userId, prefetch.contracts())) {
        result = true;
      } else if (!hasDimension && contractId == null) {
        // 无任何归属维度的独立上传文件：仅上传人本人可见（超管已在调用方放行）
        result = Objects.equals(file.getUploaderId(), userId);
      } else {
        result = reservedRead.reserved(ModelName.PROJECT_FILE, file.getId(), userId);
      }
    }
    return result;
  }

  /** 活动维度批量判定：权限逐行实时，实体与参与关系读预取数据（权限 {@code false} 时不消费实体）。 */
  private boolean judgeActivityBatch(
      Long activityId,
      Long userId,
      String modelName,
      Map<Long, BusinessActivityEntity> activityMap,
      Set<Long> participantActivityIds) {
    boolean hasViewPermission =
        permissionService.hasPermission(userId, PermissionOperates.SALES_VIEW_BUSINESS_ACTIVITY);
    BusinessActivityEntity activity = hasViewPermission ? activityMap.get(activityId) : null;
    boolean recordVisible =
        activity != null
            && (Objects.equals(activity.getCreatorId(), userId)
                || participantActivityIds.contains(activityId));
    return recordVisible || reservedRead.reserved(modelName, activityId, userId);
  }

  /** 商机维度批量判定：权限逐行实时，实体读预取数据（权限 {@code false} 时不消费实体）。 */
  private boolean judgeOpportunityBatch(
      Long opportunityId, Long userId, Map<Long, SalesOpportunityEntity> opportunityMap) {
    boolean hasViewPermission =
        permissionService.hasPermission(userId, PermissionOperates.SALES_VIEW_SALE_OPPORTUNITY);
    SalesOpportunityEntity opportunity =
        hasViewPermission ? opportunityMap.get(opportunityId) : null;
    return opportunity != null
        && (Objects.equals(opportunity.getOwnerId(), userId)
            || Objects.equals(opportunity.getCreatorId(), userId)
            || Objects.equals(opportunity.getApproverId(), userId));
  }

  /** 合同维度批量判定：权限逐行实时，实体读预取数据（权限 {@code false} 时不消费实体）。 */
  private boolean judgeContractBatch(
      Long contractId, Long userId, Map<Long, ContractEntity> contractMap) {
    boolean hasViewPermission =
        permissionService.hasPermission(userId, PermissionOperates.SALES_VIEW_CONTRACT);
    ContractEntity contract = hasViewPermission ? contractMap.get(contractId) : null;
    return contract != null
        && (Objects.equals(contract.getOwnerId(), userId)
            || Objects.equals(contract.getCreatorId(), userId));
  }

  /** 与单行 {@link #resolveContractId} 逐字等价：订单项命中则其合同 ID 覆盖 file.contractId。 */
  private static Long effectiveContractId(
      ProjectFileEntity file, Map<Long, ContractOrderItemEntity> orderItemMap) {
    Long result = file.getContractId();
    if (file.getOrderId() != null) {
      ContractOrderItemEntity orderItem = orderItemMap.get(file.getOrderId());
      if (orderItem != null && orderItem.getContractId() != null) {
        result = orderItem.getContractId();
      }
    }
    return result;
  }

  /** 按取值函数收集行集内非空维度 ID（去重，保持首次出现顺序）。 */
  private static Set<Long> collectIds(
      List<ProjectFileEntity> files, Function<ProjectFileEntity, Long> getter) {
    Set<Long> ids = new LinkedHashSet<>();
    for (ProjectFileEntity file : files) {
      if (file != null) {
        Long id = getter.apply(file);
        if (id != null) {
          ids.add(id);
        }
      }
    }
    return ids;
  }

  /** 实体列表转 ID 索引（跳过空元素；ID 冲突以先到者为准，与单行 selectById 的确定性一致）。 */
  private static <T> Map<Long, T> toEntityMap(List<T> entities, Function<T, Long> keyGetter) {
    Map<Long, T> map = new LinkedHashMap<>();
    if (entities != null) {
      for (T entity : entities) {
        if (entity != null) {
          Long key = keyGetter.apply(entity);
          if (key != null) {
            map.putIfAbsent(key, entity);
          }
        }
      }
    }
    return map;
  }
}
