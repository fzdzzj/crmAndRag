package com.slz.crm.server.service;

import com.slz.crm.pojo.entity.CustomerCompanyEntity;
import com.slz.crm.pojo.entity.CustomerContactEntity;
import com.slz.crm.pojo.entity.SalesOpportunityEntity;

/**
 * 商机、客户公司和联系人单条详情的对象级读取授权。
 */
public interface BusinessRecordAccessService {

    boolean assertCanReadOpportunity(SalesOpportunityEntity opportunity);

    boolean assertCanReadCompany(CustomerCompanyEntity company);

    boolean assertCanReadContact(CustomerContactEntity contact);
}
