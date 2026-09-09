package com.slz.crm.server.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.slz.crm.pojo.entity.DataShareEntity;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface DataShareMapper extends BaseMapper<DataShareEntity> {

    /**
     * 查询用户被共享的资源ID列表
     * <p>包括直接共享给用户和共享给用户角色的资源</p>
     *
     * @param userId       用户ID
     * @param roleId       角色ID
     * @param resourceType 资源类型(表名)
     * @return 资源ID列表
     */
    List<Long> selectSharedResourceIds(@Param("userId") Long userId,
                                       @Param("roleId") Long roleId,
                                       @Param("resourceType") String resourceType);
}
