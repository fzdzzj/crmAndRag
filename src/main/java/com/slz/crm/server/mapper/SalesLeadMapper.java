package com.slz.crm.server.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.slz.crm.pojo.entity.SalesLeadEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 销售线索 Mapper（add-lead-management-and-conversion-funnel 任务 1.3）。
 *
 * <p>基础 CRUD 由 MyBatis-Plus BaseMapper 提供；线索按状态/负责人/模糊查询走 ServiceImpl 的 LambdaQueryWrapper，无需额外
 * XML。
 */
@Mapper
public interface SalesLeadMapper extends BaseMapper<SalesLeadEntity> {}
