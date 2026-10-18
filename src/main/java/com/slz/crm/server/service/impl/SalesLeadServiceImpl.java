package com.slz.crm.server.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.slz.crm.common.enumeration.ErrorCode;
import com.slz.crm.common.exiception.BaseException;
import com.slz.crm.common.untils.BaseUnit;
import com.slz.crm.knowledge.ingestion.CustomerIngestionService;
import com.slz.crm.knowledge.ingestion.SalesOpportunityIngestionService;
import com.slz.crm.pojo.dto.LeadConvertDTO;
import com.slz.crm.pojo.dto.SalesLeadDTO;
import com.slz.crm.pojo.dto.SalesLeadQueryDTO;
import com.slz.crm.pojo.entity.CustomerCompanyEntity;
import com.slz.crm.pojo.entity.CustomerContactEntity;
import com.slz.crm.pojo.entity.SalesLeadEntity;
import com.slz.crm.pojo.entity.SalesOpportunityEntity;
import com.slz.crm.pojo.vo.LeadConvertVO;
import com.slz.crm.pojo.vo.SalesLeadVO;
import com.slz.crm.server.annotation.Privacy;
import com.slz.crm.server.mapper.CustomerCompanyMapper;
import com.slz.crm.server.mapper.CustomerContactMapper;
import com.slz.crm.server.mapper.SalesLeadMapper;
import com.slz.crm.server.mapper.SalesOpportunityMapper;
import com.slz.crm.server.mapper.UserMapper;
import com.slz.crm.server.service.SalesLeadService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 销售线索服务实现（add-lead-management-and-conversion-funnel 任务 2.2/2.3）。
 *
 * <p>核心「一键转化」{@link #convert}：单事务内创建客户公司、首要联系人及可选销售机会，并把线索状态置为已转化 （2）、回填转化关联 ID 与转化时间；事务提交后经语义 hook
 * 异步联动向量同步（异常 WARN 不阻断业务主流程）。
 */
@Slf4j
@Service
public class SalesLeadServiceImpl extends ServiceImpl<SalesLeadMapper, SalesLeadEntity>
    implements SalesLeadService {

  /** 线索状态：已转化。 */
  private static final int STATUS_CONVERTED = 2;

  /** 线索状态：已关闭无效。 */
  private static final int STATUS_INVALID = 3;

  @Autowired private SalesLeadMapper salesLeadMapper;

  @Autowired private CustomerCompanyMapper customerCompanyMapper;

  @Autowired private CustomerContactMapper customerContactMapper;

  @Autowired private SalesOpportunityMapper salesOpportunityMapper;

  @Autowired private UserMapper userMapper;

  /** 客户语义向量摄取（转换生成的客户公司异步联动；缺失时整体跳过，不阻断业务）。 */
  @Autowired(required = false)
  private CustomerIngestionService customerIngestionService;

  /** 商机语义向量摄取（转换生成的销售机会异步联动；缺失时整体跳过，不阻断业务）。 */
  @Autowired(required = false)
  private SalesOpportunityIngestionService salesOpportunityIngestionService;

  /** 语义向量异步任务执行器（事务后异步派发；单测可注入直接执行器）。 */
  @Autowired
  @Qualifier("embeddingTaskExecutor")
  private Executor embeddingTaskExecutor;

  @Override
  public SalesLeadVO add(SalesLeadDTO dto) {
    if (dto == null
        || !StringUtils.hasText(dto.getName())
        || !StringUtils.hasText(dto.getCompanyName())) {
      throw new BaseException(ErrorCode.PARAM_EMPTY, "线索姓名与公司名称不能为空");
    }
    Long operatorId = BaseUnit.getCurrentId();
    SalesLeadEntity entity = new SalesLeadEntity();
    BeanUtils.copyProperties(dto, entity);
    entity.setId(null);
    entity.setCreatorId(operatorId);
    entity.setOwnerId(dto.getOwnerId() == null ? operatorId : dto.getOwnerId());
    entity.setStatus(dto.getStatus() == null ? 0 : dto.getStatus());
    entity.setIsDeleted(0);
    save(entity);
    return SalesLeadAssembler.toView(entity, userMapper);
  }

  @Override
  @Privacy
  public SalesLeadVO getDetail(Long id) {
    return SalesLeadAssembler.toView(requireLead(id), userMapper);
  }

  @Override
  public boolean update(SalesLeadDTO dto) {
    if (dto == null || dto.getId() == null) {
      throw new BaseException(ErrorCode.PARAM_EMPTY, "线索 ID 不能为空");
    }
    SalesLeadEntity existing = requireLead(dto.getId());
    if (existing.getStatus() != null && existing.getStatus() == STATUS_CONVERTED) {
      throw new BaseException(ErrorCode.PARAM_FORMAT_ERROR, "已转化线索不可修改");
    }
    SalesLeadEntity entity = new SalesLeadEntity();
    BeanUtils.copyProperties(dto, entity);
    entity.setIsDeleted(null);
    return updateById(entity);
  }

  @Override
  public boolean deleteById(Long id) {
    SalesLeadEntity existing = requireLead(id);
    if (existing.getStatus() != null && existing.getStatus() == STATUS_CONVERTED) {
      throw new BaseException(ErrorCode.PARAM_FORMAT_ERROR, "已转化线索不可删除");
    }
    SalesLeadEntity update = new SalesLeadEntity();
    update.setId(id);
    update.setIsDeleted(1);
    return updateById(update);
  }

  @Override
  public boolean closeInvalid(Long id) {
    SalesLeadEntity existing = requireLead(id);
    if (existing.getStatus() != null && existing.getStatus() == STATUS_CONVERTED) {
      throw new BaseException(ErrorCode.PARAM_FORMAT_ERROR, "已转化线索不可关闭为无效");
    }
    SalesLeadEntity update = new SalesLeadEntity();
    update.setId(id);
    update.setStatus(STATUS_INVALID);
    return updateById(update);
  }

  @Override
  @Privacy
  public Page<SalesLeadVO> queryPage(SalesLeadQueryDTO query, Integer pageNum, Integer pageSize) {
    if (query == null) {
      query = new SalesLeadQueryDTO();
    }
    if (pageNum == null || pageNum <= 0) {
      pageNum = 1;
    }
    if (pageSize == null || pageSize <= 0) {
      pageSize = 10;
    }
    LambdaQueryWrapper<SalesLeadEntity> wrapper = new LambdaQueryWrapper<>();
    wrapper.eq(SalesLeadEntity::getIsDeleted, 0);
    applyFilters(wrapper, query);
    wrapper.orderByDesc(SalesLeadEntity::getCreateTime);

    Page<SalesLeadEntity> entityPage = page(new Page<>(pageNum, pageSize), wrapper);
    Page<SalesLeadVO> voPage =
        new Page<>(entityPage.getCurrent(), entityPage.getSize(), entityPage.getTotal());
    List<SalesLeadVO> voList =
        entityPage.getRecords().stream()
            .map(e -> SalesLeadAssembler.toView(e, userMapper))
            .collect(Collectors.toList());
    voPage.setRecords(voList);
    return voPage;
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public LeadConvertVO convert(LeadConvertDTO dto) {
    if (dto == null || dto.getLeadId() == null) {
      throw new BaseException(ErrorCode.PARAM_EMPTY, "待转化线索 ID 不能为空");
    }
    SalesLeadEntity lead = requireLead(dto.getLeadId());
    assertConvertible(lead);
    Long operatorId = BaseUnit.getCurrentId();

    CustomerCompanyEntity company = SalesLeadAssembler.buildCompany(lead, operatorId);
    customerCompanyMapper.insert(company);

    CustomerContactEntity contact =
        SalesLeadAssembler.buildContact(lead, operatorId, company.getId());
    customerContactMapper.insert(contact);

    SalesOpportunityEntity opportunity = null;
    if (Boolean.TRUE.equals(dto.getCreateOpportunity())) {
      opportunity =
          SalesLeadAssembler.buildOpportunity(
              dto, lead, operatorId, company.getId(), contact.getId());
      salesOpportunityMapper.insert(opportunity);
    }

    markConverted(lead, company, contact, opportunity);
    LeadConvertVO vo = result(company, contact, opportunity);
    scheduleVectorSync(company.getId(), opportunity == null ? null : opportunity.getId());
    return vo;
  }

  /** 校验线索可转化：已转化/已关闭无效均不可转化。 */
  private void assertConvertible(SalesLeadEntity lead) {
    if (lead.getStatus() != null
        && (lead.getStatus() == STATUS_CONVERTED || lead.getStatus() == STATUS_INVALID)) {
      throw new BaseException(ErrorCode.PARAM_FORMAT_ERROR, "该线索状态不可转化（已转化或已关闭无效）");
    }
  }

  /** 回填线索转化状态与关联 ID。 */
  private void markConverted(
      SalesLeadEntity lead,
      CustomerCompanyEntity company,
      CustomerContactEntity contact,
      SalesOpportunityEntity opportunity) {
    SalesLeadEntity update = new SalesLeadEntity();
    update.setId(lead.getId());
    update.setStatus(STATUS_CONVERTED);
    update.setConvertedCompanyId(company.getId());
    update.setConvertedContactId(contact.getId());
    update.setConvertedOpportunityId(opportunity == null ? null : opportunity.getId());
    update.setConvertedTime(LocalDateTime.now());
    updateById(update);
  }

  /** 组装转化结果 VO。 */
  private LeadConvertVO result(
      CustomerCompanyEntity company,
      CustomerContactEntity contact,
      SalesOpportunityEntity opportunity) {
    LeadConvertVO vo = new LeadConvertVO();
    vo.setCompanyId(company.getId());
    vo.setContactId(contact.getId());
    vo.setOpportunityId(opportunity == null ? null : opportunity.getId());
    return vo;
  }

  /**
   * 事务提交后异步联动语义向量同步（生成标志量入 action，主线程零等待）。
   *
   * @param companyId 新建客户公司主键
   * @param opportunityId 新建销售机会主键（未建为 null）
   */
  private void scheduleVectorSync(Long companyId, Long opportunityId) {
    if (embeddingTaskExecutor == null
        || (customerIngestionService == null && salesOpportunityIngestionService == null)) {
      return;
    }
    CustomerSemanticHookSupport.runAfterCommitAsync(
        "线索转化-客户/商机",
        embeddingTaskExecutor,
        () -> {
          if (customerIngestionService != null) {
            CustomerSemanticHookSupport.reingestById(
                companyId, customerIngestionService, customerCompanyMapper::selectById);
          }
          if (opportunityId != null && salesOpportunityIngestionService != null) {
            SalesOpportunitySemanticHookSupport.reingestById(
                opportunityId,
                salesOpportunityIngestionService,
                salesOpportunityMapper::selectById);
          }
        });
  }

  /** 组合查询条件。 */
  private void applyFilters(LambdaQueryWrapper<SalesLeadEntity> wrapper, SalesLeadQueryDTO query) {
    if (StringUtils.hasText(query.getName())) {
      wrapper.like(SalesLeadEntity::getName, query.getName());
    }
    if (StringUtils.hasText(query.getCompanyName())) {
      wrapper.like(SalesLeadEntity::getCompanyName, query.getCompanyName());
    }
    if (StringUtils.hasText(query.getPhone())) {
      wrapper.like(SalesLeadEntity::getPhone, query.getPhone());
    }
    if (StringUtils.hasText(query.getSource())) {
      wrapper.eq(SalesLeadEntity::getSource, query.getSource());
    }
    if (query.getStatus() != null) {
      wrapper.eq(SalesLeadEntity::getStatus, query.getStatus());
    }
    if (query.getOwnerId() != null) {
      wrapper.eq(SalesLeadEntity::getOwnerId, query.getOwnerId());
    }
    if (query.getMinCreateTime() != null) {
      wrapper.ge(SalesLeadEntity::getCreateTime, query.getMinCreateTime());
    }
    if (query.getMaxCreateTime() != null) {
      wrapper.le(SalesLeadEntity::getCreateTime, query.getMaxCreateTime());
    }
  }

  /** 查询线索并按需抛出不存在异常（ID_NOT_EXISTS 参数化取值 "线索不存在"）。 */
  private SalesLeadEntity requireLead(Long id) {
    SalesLeadEntity entity = getById(id);
    if (entity == null || (entity.getIsDeleted() != null && entity.getIsDeleted() == 1)) {
      throw new BaseException(ErrorCode.ID_NOT_EXISTS, "线索不存在");
    }
    return entity;
  }
}
