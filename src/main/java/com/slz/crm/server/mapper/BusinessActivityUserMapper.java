package com.slz.crm.server.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.slz.crm.pojo.entity.BusinessActivityUserEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface BusinessActivityUserMapper extends BaseMapper<BusinessActivityUserEntity> {
    /**
     * 根据关联ID、商业活动ID和用户ID查询关联记录是否存在
     * @param activityId 商业活动ID
     * @param userId 用户ID
     * @return 如果存在关联记录则返回1，否则返回0
     */
    int existsByActivityIdAndUserId(@Param("activityId") Long activityId, @Param("userId") Long userId);
    /**
     * 根据商业活动ID和用户ID删除关联记录
     * @param activityId 商业活动ID
     * @param userId 用户ID
     */
    void deleteByActivityIdAndUserId(@Param("activityId") Long activityId, @Param("userId") Long userId);

    /**
     * 批量查询活动的用户关联记录
     * @param activityIds 活动ID列表
     * @return 用户关联记录列表
     */
    List<BusinessActivityUserEntity> selectByActivityIds(@Param("activityIds") List<Long> activityIds);

    void deleteByActivityId(@Param("activityId") Long activityId);
}
