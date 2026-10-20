package com.slz.crm.server.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.slz.crm.pojo.entity.ProjectFileEntity;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ProjectFileMapper extends BaseMapper<ProjectFileEntity> {

  /**
   * 多值 INSERT 批量落库（A-5+A-16：替代逐条 insert，事务边界由调用方短事务界定）。
   *
   * @param entities 待插入的项目文件实体列表
   * @return 插入行数
   */
  int insertBatch(@Param("entities") List<ProjectFileEntity> entities);
}
