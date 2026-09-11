package com.slz.crm.server.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.slz.crm.knowledge.entity.KnowledgeBaseEntity;
import org.apache.ibatis.annotations.Mapper;

/** 知识库表 Mapper；保留在既有 MapperScan 包内。 */
@Mapper
public interface KnowledgeBaseMapper extends BaseMapper<KnowledgeBaseEntity> {
}
