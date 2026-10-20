package com.slz.crm.server.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.slz.crm.pojo.entity.ApprovalAttachmentEntity;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ApprovalAttachmentMapper extends BaseMapper<ApprovalAttachmentEntity> {

  /**
   * 多值 INSERT 批量落库（E-15：替代 saveBatch 内逐条 insert，事务边界由调用方短事务界定）。
   *
   * @param entities 待插入的附件实体列表
   * @return 插入行数
   */
  int insertBatch(@Param("entities") List<ApprovalAttachmentEntity> entities);
}
