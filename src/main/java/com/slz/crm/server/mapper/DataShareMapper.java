package com.slz.crm.server.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.slz.crm.pojo.entity.DataShareEntity;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface DataShareMapper extends BaseMapper<DataShareEntity> {

  /**
   * 查询用户被共享的资源ID列表
   *
   * <p>包括直接共享给用户和共享给用户角色的资源
   *
   * @param userId 用户ID
   * @param roleId 角色ID
   * @param resourceType 资源类型(表名)
   * @return 资源ID列表
   */
  List<Long> selectSharedResourceIds(
      @Param("userId") Long userId,
      @Param("roleId") Long roleId,
      @Param("resourceType") String resourceType);
}
