package com.slz.crm.server.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.slz.crm.pojo.entity.CustomerPoolLogEntity;
import org.apache.ibatis.annotations.Mapper;

/** 客户公海流转记录 Mapper（add-customer-public-sea-pool 任务 1.3，映射 customer_pool_log 表）。 */
@Mapper
public interface CustomerPoolLogMapper extends BaseMapper<CustomerPoolLogEntity> {}
