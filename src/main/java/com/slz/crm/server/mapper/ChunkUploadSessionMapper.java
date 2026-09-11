package com.slz.crm.server.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.slz.crm.knowledge.entity.ChunkUploadSessionEntity;
import org.apache.ibatis.annotations.Mapper;

/** 分片上传会话表 Mapper。 */
@Mapper
public interface ChunkUploadSessionMapper extends BaseMapper<ChunkUploadSessionEntity> {
}
