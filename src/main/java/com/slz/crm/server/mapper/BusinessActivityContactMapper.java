package com.slz.crm.server.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.slz.crm.pojo.entity.BusinessActivityContactEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface BusinessActivityContactMapper extends BaseMapper<BusinessActivityContactEntity> {

    int existsByActivityIdAndContactId(@Param("activityId") Long activityId, @Param("contactId") Long contactId);

    void deleteByActivityIdAndContactId(@Param("activityId") Long activityId, @Param("contactId") Long contactId);

    void deleteByActivityId(@Param("activityId") Long activityId);
}
