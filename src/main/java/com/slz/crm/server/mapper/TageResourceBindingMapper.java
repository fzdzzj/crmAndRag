package com.slz.crm.server.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.slz.crm.pojo.entity.TageResourceBindingEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 标签资源绑定 Mapper
 */
@Mapper
public interface TageResourceBindingMapper extends BaseMapper<TageResourceBindingEntity> {

    /**
     * 查询标签绑定的资源ID列表
     *
     * @param tageIds      标签ID列表
     * @param resourceType 资源类型(表名)
     * @return 资源ID列表
     */
    List<Long> selectResourceIdsByTageIds(@Param("tageIds") List<Long> tageIds,
                                           @Param("resourceType") String resourceType);
}
