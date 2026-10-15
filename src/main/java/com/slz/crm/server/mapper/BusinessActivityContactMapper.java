package com.slz.crm.server.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.slz.crm.pojo.entity.BusinessActivityContactEntity;
import java.util.Set;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface BusinessActivityContactMapper extends BaseMapper<BusinessActivityContactEntity> {

  int existsByActivityIdAndContactId(
      @Param("activityId") Long activityId, @Param("contactId") Long contactId);

  void deleteByActivityIdAndContactId(
      @Param("activityId") Long activityId, @Param("contactId") Long contactId);

  void deleteByActivityId(@Param("activityId") Long activityId);

  /** 查询某商业活动关联的全部联系人 ID（add-customer-public-sea-pool 任务 3.2，跟进活跃度联动）。 */
  @Select("select contact_id from business_activity_contact where activity_id = #{activityId}")
  Set<Long> selectContactIdsByActivity(@Param("activityId") Long activityId);
}
