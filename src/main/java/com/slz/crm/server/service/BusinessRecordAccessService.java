package com.slz.crm.server.service;

import com.slz.crm.pojo.entity.ContactTaskEntity;
import com.slz.crm.pojo.entity.ContractEntity;
import com.slz.crm.pojo.entity.CustomerCompanyEntity;
import com.slz.crm.pojo.entity.CustomerContactEntity;
import com.slz.crm.pojo.entity.SalesOpportunityEntity;

/** 商机、客户公司、联系人、合同与联络任务单条详情的对象级读取授权。 */
public interface BusinessRecordAccessService {

  boolean assertCanReadOpportunity(SalesOpportunityEntity opportunity);

  boolean assertCanReadCompany(CustomerCompanyEntity company);

  boolean assertCanReadContact(CustomerContactEntity contact);

  /**
   * 合同详情的对象级读取授权（harden-consistency-datascope-and-gate 模块 B）：超级管理员、负责人、创建人、
   * 同部门（及以下）与显式数据共享放行；无登录身份、实体缺失或越权一律抛 12002 {@code PERMISSION_DENIED}。
   *
   * @return 放行时恒为 true（仅表达"正常数据范围内"，合同无协助范围语义）
   */
  boolean assertCanReadContract(ContractEntity contract);

  /**
   * 联络任务详情的对象级读取授权（模块 B）：任务执行人（负责人）、指派人、创建人、同部门（及以下）与 显式数据共享放行；无登录身份、实体缺失或越权一律抛 12002 {@code
   * PERMISSION_DENIED}。
   *
   * @return 放行时恒为 true（仅表达"正常数据范围内"，任务无协助范围语义）
   */
  boolean assertCanReadTask(ContactTaskEntity task);
}
