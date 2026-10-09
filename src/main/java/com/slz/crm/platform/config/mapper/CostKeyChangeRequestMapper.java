package com.slz.crm.platform.config.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.slz.crm.platform.config.entity.CostKeyChangeRequestEntity;
import org.apache.ibatis.annotations.Mapper;

/** 成本键变更申请单 Mapper（add-cost-key-approval-workflow 任务 1.3）。 */
@Mapper
public interface CostKeyChangeRequestMapper extends BaseMapper<CostKeyChangeRequestEntity> {}
