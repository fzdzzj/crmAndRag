package com.slz.crm.server.service.impl;

import java.time.LocalDateTime;

/**
 * 协助列表用的业务记录摘要值对象（tighten-pmd-residual-325 任务 6.5，拆自 AssistRequestServiceImpl 的内部类）。
 *
 * <p>只承载「标题 / 内容 / 时间 + 商机公司联系人索引」七个不可变字段，采集方与回填方共用。
 */
class AssistRecordBrief {

  final String title;

  final String content;

  final LocalDateTime time;

  final Long opportunityId;

  final String opportunityName;

  final Long companyId;

  final Long contactId;

  AssistRecordBrief(
      String title,
      String content,
      LocalDateTime time,
      Long opportunityId,
      String opportunityName,
      Long companyId,
      Long contactId) {
    this.title = title;
    this.content = content;
    this.time = time;
    this.opportunityId = opportunityId;
    this.opportunityName = opportunityName;
    this.companyId = companyId;
    this.contactId = contactId;
  }
}
