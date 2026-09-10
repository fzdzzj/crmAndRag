package com.slz.crm.server.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.slz.crm.knowledge.entity.UploadedFileEntity;
import org.apache.ibatis.annotations.Mapper;

/** 知识库上传文件表 Mapper。 */
@Mapper
public interface UploadedFileMapper extends BaseMapper<UploadedFileEntity> {
}
