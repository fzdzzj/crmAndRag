package com.slz.crm.server.service;

import com.slz.crm.pojo.entity.AssistRequestEntity;
import com.slz.crm.pojo.vo.AssistRelatedRecordVO;

/**
 * 解析协助记录实际关联的商机、客户公司和联系人。
 *
 * <p>该解析结果同时用于协助详情展示和对象级授权，避免两处各自回查业务表而产生不同的可见范围。
 */
public interface AssistRelatedRecordResolver {

  /**
   * 解析关联对象 ID，不填充展示名称。
   *
   * <p>联络任务的直接公司、联系人优先；只有直接字段为空时，才由关联商机补齐。
   */
  AssistRelatedRecordVO resolve(AssistRequestEntity assist);
}
