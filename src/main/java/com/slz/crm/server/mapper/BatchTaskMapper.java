package com.slz.crm.server.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.slz.crm.knowledge.entity.BatchTaskEntity;
import org.apache.ibatis.annotations.Mapper;

/** 批量上传任务表 Mapper。 */
@Mapper
public interface BatchTaskMapper extends BaseMapper<BatchTaskEntity> {}
