package com.slz.crm.server.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.slz.crm.pojo.entity.BusinessActivityContactEntity;
import java.util.Collection;
import java.util.List;
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

  /**
   * 一次 IN 查询某商业活动已存在的联系人关联（A-8 集合化：替代逐个 exists 的 check-then-act 循环）
   *
   * @param activityId 商业活动ID
   * @param contactIds 待判定的联系人ID集合（可为请求内重复 id，去重由调用方负责）
   * @return 已存在的联系人ID集合
   */
  Set<Long> selectExistingContactIds(
      @Param("activityId") Long activityId, @Param("contactIds") Collection<Long> contactIds);

  /**
   * 按商业活动ID批量删除多个联系人关联（单条 delete + IN）
   *
   * @param activityId 商业活动ID
   * @param contactIds 联系人ID集合
   * @return 删除行数
   */
  int deleteByActivityIdAndContactIds(
      @Param("activityId") Long activityId, @Param("contactIds") Collection<Long> contactIds);

  /**
   * 多值 INSERT 批量新增联系人关联（create_time 走库默认 CURRENT_TIMESTAMP）
   *
   * @param entities 关联记录列表
   * @return 插入行数
   */
  int batchInsert(@Param("entities") List<BusinessActivityContactEntity> entities);
}
