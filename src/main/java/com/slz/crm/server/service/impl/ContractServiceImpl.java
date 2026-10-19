package com.slz.crm.server.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.slz.crm.common.enumeration.ErrorCode;
import com.slz.crm.common.exiception.BaseException;
import com.slz.crm.common.untils.BaseUnit;
import com.slz.crm.common.untils.ForeignKeyDeleteUtil;
import com.slz.crm.knowledge.ingestion.ContractIngestionService;
import com.slz.crm.pojo.dto.ContractDTO;
import com.slz.crm.pojo.dto.OrderDTO;
import com.slz.crm.pojo.entity.ContractEntity;
import com.slz.crm.pojo.entity.SalesOpportunityEntity;
import com.slz.crm.pojo.vo.ContractANDOrderVO;
import com.slz.crm.pojo.vo.ContractVO;
import com.slz.crm.pojo.vo.OrderVO;
import com.slz.crm.server.annotation.Privacy;
import com.slz.crm.server.mapper.ContractMapper;
import com.slz.crm.server.mapper.SalesOpportunityMapper;
import com.slz.crm.server.service.BusinessRecordAccessService;
import com.slz.crm.server.service.ContractOrderItemService;
import com.slz.crm.server.service.ContractService;
import com.slz.crm.server.service.DataConvertService;
import java.util.*;
import java.util.concurrent.Executor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Slf4j
public class ContractServiceImpl extends ServiceImpl<ContractMapper, ContractEntity>
    implements ContractService {

  @Autowired private SalesOpportunityMapper salesOpportunityMapper;
  @Autowired private ForeignKeyDeleteUtil foreignKeyDeleteUtil;
  @Autowired private ContractOrderItemService contractOrderItemService;
  @Autowired private DataConvertService dataConvertService;
  @Autowired private BusinessRecordAccessService businessRecordAccessService;

  /** 合同语义向量摄取（add-unified-business-semantic-retrieval 任务 2.2；缺失时 hook 整体跳过）。 */
  @Autowired(required = false)
  private ContractIngestionService contractIngestionService;

  /** 合同语义向量异步任务执行器（add-unified-business-semantic-retrieval 任务 2.2；异步非阻塞核心依赖）。 */
  @Autowired
  @Qualifier("embeddingTaskExecutor")
  private Executor embeddingTaskExecutor;

  /**
   * 语义向量同步闸（任务 2.2，委托 {@link ContractSemanticHookSupport#runAfterCommitAsync}）：事务激活时注册
   * afterCommit，提交后异步派发至 embeddingTaskExecutor，无事务环境同样异步派发；semantic 侧未装配或异常均不阻断业务主流程，主线程零等待。
   *
   * @param scene 场景描述（日志定位用）
   * @param action 向量同步动作
   */
  private void runSemanticSync(String scene, Runnable action) {
    if (contractIngestionService != null && embeddingTaskExecutor != null) {
      ContractSemanticHookSupport.runAfterCommitAsync(scene, embeddingTaskExecutor, action);
    }
  }

  /** 回查完整实体后 upsert 语义向量（任务 2.2）；仅在 runSemanticSync 的 action 内调用。 */
  private void reingestSemanticById(Long id) {
    ContractSemanticHookSupport.reingestById(id, contractIngestionService, this::getById);
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public ContractVO create(ContractDTO dto) {

    ContractEntity entity = new ContractEntity();
    BeanUtils.copyProperties(dto, entity);
    Long currentId = BaseUnit.getCurrentId();
    entity.setCreatorId(currentId);

    if (entity.getContractStatus() == null) {
      entity.setContractStatus(0);
    }

    if (entity.getContractNo() == null) {
      entity.setContractNo(UUID.randomUUID().toString());
    }

    // 根据商机ID查询公司客户id（统一从商机获取公司ID，忽略前端传入的companyId）
    if (entity.getOpportunityId() == null) {
      throw new BaseException(ErrorCode.OPPORTUNITY_NOT_EXISTS, "商机ID不能为空");
    }

    SalesOpportunityEntity salesOpportunityEntity =
        salesOpportunityMapper.selectOne(
            new LambdaQueryWrapper<SalesOpportunityEntity>()
                .eq(SalesOpportunityEntity::getId, entity.getOpportunityId()));

    if (salesOpportunityEntity == null) {
      throw new BaseException(
          ErrorCode.OPPORTUNITY_NOT_EXISTS, "商机不存在，商机ID：" + entity.getOpportunityId());
    }

    // 业务规则验证：已关闭的商机（阶段为5）不能创建合同
    if (salesOpportunityEntity.getStage() == 5) {
      throw new BaseException(
          ErrorCode.OPPORTUNITY_MUST_BE_CLOSED, "已关闭的商机不能创建合同，商机ID：" + entity.getOpportunityId());
    }

    // 业务规则验证：商机已被删除则不能创建合同
    if (salesOpportunityEntity.getIsDeleted()) {
      throw new BaseException(
          ErrorCode.OPPORTUNITY_NOT_EXISTS, "商机已被删除，不能创建合同，商机ID：" + entity.getOpportunityId());
    }

    // 统一从商机中获取公司ID
    entity.setCompanyId(salesOpportunityEntity.getCompanyId());

    ContractVO result;
    if (!save(entity)) {
      result = null;
    } else {
      String companyName = dataConvertService.getCompanyName(entity.getCompanyId());
      String opportunityName = dataConvertService.getOpportunityName(entity.getOpportunityId());
      String ownerName = dataConvertService.getUserName(entity.getOwnerId());
      String creatorName = dataConvertService.getUserName(entity.getCreatorId());
      result = ContractVO.fromEntity(entity, companyName, opportunityName, ownerName, creatorName);
    }

    // add-unified-business-semantic-retrieval 任务 2.2：创建成功提交后异步增量 upsert 语义向量
    // （回查完整实体含订单明细/回款附属段；异步派发，HTTP 主线程立即返回，不等待任何向量嵌入耗时）
    if (result != null && result.getId() != null) {
      runSemanticSync("创建合同", () -> reingestSemanticById(result.getId()));
    }
    return result;
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public ContractVO createWithOrders(ContractDTO contract, List<OrderDTO> orders) {
    if (contract == null) {
      throw new BaseException(ErrorCode.PARAM_EMPTY);
    }

    ContractVO created = create(contract);
    if (created == null || created.getId() == null) {
      throw new IllegalStateException("创建合同失败");
    }

    if (orders != null && !orders.isEmpty()) {
      orders.forEach(order -> order.setContractId(created.getId()));
      List<OrderVO> createdOrders = contractOrderItemService.createBatch(orders);
      if (createdOrders == null || createdOrders.size() != orders.size()) {
        throw new IllegalStateException("创建订单失败");
      }
    }

    return created;
  }

  @Override
  @Privacy
  public ContractANDOrderVO getDetailById(Long id) {
    // 参数校验
    if (id == null) {
      throw new BaseException(ErrorCode.PARAM_EMPTY);
    }

    // 1. 获取合同实体并做对象级读权限断言（越权 12002，先于任何附属数据查询）
    ContractEntity entity = getById(id);
    if (entity == null) {
      throw new BaseException(ErrorCode.CONTRACT_NOT_EXISTS);
    }
    businessRecordAccessService.assertCanReadContract(entity);

    // 2. 获取关联订单信息
    List<OrderVO> ordersVO = contractOrderItemService.getOrderListByContractId(id);

    // 3. 组装返回结果
    ContractANDOrderVO vo = new ContractANDOrderVO();
    vo.setContract(toContractVO(entity));
    vo.setOrders(ordersVO);

    return vo;
  }

  @Override
  public ContractVO getContractById(Long id) {
    ContractEntity entity = getById(id);
    if (entity == null) {
      throw new BaseException(ErrorCode.CONTRACT_NOT_EXISTS);
    }

    return toContractVO(entity);
  }

  /** 实体转 VO 并回填公司/商机/负责人/创建人名称。 */
  private ContractVO toContractVO(ContractEntity entity) {
    String companyName = dataConvertService.getCompanyName(entity.getCompanyId());
    String opportunityName = dataConvertService.getOpportunityName(entity.getOpportunityId());
    String ownerName = dataConvertService.getUserName(entity.getOwnerId());
    String creatorName = dataConvertService.getUserName(entity.getCreatorId());
    return ContractVO.fromEntity(entity, companyName, opportunityName, ownerName, creatorName);
  }

  @Override
  @Privacy
  public Page<ContractVO> contractQuery(Integer pageNum, Integer pageSize, ContractDTO dto) {
    Page<ContractEntity> page = new Page<>(pageNum, pageSize);

    LambdaQueryWrapper<ContractEntity> queryWrapper = new LambdaQueryWrapper<>();
    // 自定义查询条件
    if (dto != null) {
      if (dto.getContractName() != null && !dto.getContractName().isEmpty()) {
        queryWrapper.like(ContractEntity::getContractName, dto.getContractName());
      }
      if (dto.getContractNo() != null && !dto.getContractNo().isEmpty()) {
        queryWrapper.eq(ContractEntity::getContractNo, dto.getContractNo());
      }
      if (dto.getCompanyId() != null) {
        queryWrapper.eq(ContractEntity::getCompanyId, dto.getCompanyId());
      }
      if (dto.getContractStatus() != null) {
        queryWrapper.eq(ContractEntity::getContractStatus, dto.getContractStatus());
      }
      if (dto.getOwnerId() != null) {
        queryWrapper.eq(ContractEntity::getOwnerId, dto.getOwnerId());
      }
    }

    // 默认按创建时间倒序
    queryWrapper.orderByDesc(ContractEntity::getCreateTime);

    Page<ContractEntity> entityPage = baseMapper.selectPage(page, queryWrapper);
    return ContractVoAssembler.toVoPage(entityPage, dataConvertService);
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  @CacheEvict(value = "contractName", key = "#dto.id")
  public boolean update(ContractDTO dto) {
    if (dto.getId() == null) {
      throw new BaseException(ErrorCode.PARAM_EMPTY);
    }

    ContractEntity entity = getById(dto.getId());
    if (entity == null) {
      throw new BaseException(
          ErrorCode.CONTRACT_NOT_EXISTS, ErrorCode.CONTRACT_NOT_EXISTS.getMessage());
    }

    BeanUtils.copyProperties(dto, entity);
    //        entity.setUpdateTime(BaseUnit.getCurrentTime());

    boolean updated = updateById(entity);

    // add-unified-business-semantic-retrieval 任务 2.2：更新成功提交后异步重嵌语义向量
    // （回查完整实体防 DTO 部分字段丢键；afterCommit/无事务均异步派发，主线程零等待）
    runSemanticSync("更新合同", () -> reingestSemanticById(dto.getId()));

    return updated;
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  @CacheEvict(value = "contractName", allEntries = true)
  public int batchDelete(List<Long> ids) {
    if (ids == null || ids.isEmpty()) {
      throw new BaseException(ErrorCode.PARAM_EMPTY);
    }

    // 收集不符合状态的合同ID
    List<String> invalidIds = new ArrayList<>();
    List<ContractEntity> entities = listByIds(ids);
    for (ContractEntity entity : entities) {
      if (entity.getContractStatus() != 4) {
        invalidIds.add(entity.getId().toString());
      }
    }

    // 有不符合的，抛拼接后的提示
    if (!invalidIds.isEmpty()) {
      String msg =
          ErrorCode.CONTRACT_STATUS_ERROR.getMessage()
              + "，不符合的合同ID："
              + String.join(",", invalidIds);
      throw new BaseException(ErrorCode.CONTRACT_STATUS_ERROR, msg);
    }

    foreignKeyDeleteUtil.deleteCascade(ContractEntity.class, ids, 0);
    int deleted = baseMapper.deleteBatchIds(ids);

    // add-unified-business-semantic-retrieval 任务 2.2：删除事务提交后异步失效向量（删除即失效）
    runSemanticSync(
        "删除合同",
        () ->
            ids.forEach(
                id -> ContractSemanticHookSupport.deleteById(id, contractIngestionService)));

    return deleted;
  }

  @Override
  public Page<ContractVO> getAllContract(Integer pageNum, Integer pageSize) {

    Page<ContractEntity> page = new Page<>(pageNum, pageSize);
    Page<ContractEntity> pageResult =
        baseMapper.selectPage(
            page,
            new LambdaQueryWrapper<ContractEntity>().orderByDesc(ContractEntity::getCreateTime));
    return ContractVoAssembler.toVoPage(pageResult, dataConvertService);
  }
}
