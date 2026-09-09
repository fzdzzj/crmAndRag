package com.slz.crm.server.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.slz.crm.pojo.entity.BusinessActivityEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface BusinessActivityMapper extends BaseMapper<BusinessActivityEntity> {

    /**
     * 根据商机ID查询所有业务活动
     * @param opportunityId 商机ID
     * @return 业务活动列表,按活动时间升序排列
     */
    @Select("SELECT * FROM business_activity " +
            "WHERE opportunity_id = #{opportunityId} " +
            "ORDER BY activity_time ASC")
    List<BusinessActivityEntity> selectByOpportunityId(@Param("opportunityId") Long opportunityId);

    /**
     * 根据任务ID查询所有业务活动
     * @param taskId 任务ID
     * @return 业务活动列表,按创建时间倒序排列
     */
    @Select("SELECT * FROM business_activity " +
            "WHERE task_id = #{taskId} " +
            "ORDER BY create_time DESC")
    List<BusinessActivityEntity> selectByTaskId(@Param("taskId") Long taskId);

}
