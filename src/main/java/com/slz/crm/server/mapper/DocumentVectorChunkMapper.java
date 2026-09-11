package com.slz.crm.server.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.slz.crm.knowledge.entity.DocumentVectorChunkEntity;
import org.apache.ibatis.annotations.Mapper;

/** 文档向量切片快照表 Mapper。 */
@Mapper
public interface DocumentVectorChunkMapper extends BaseMapper<DocumentVectorChunkEntity> {
}
