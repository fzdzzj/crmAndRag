package com.slz.crm.server.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.slz.crm.pojo.entity.BusinessActivityUserEntity;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface BusinessActivityUserMapper extends BaseMapper<BusinessActivityUserEntity> {
  /**
   * 根据关联ID、商业活动ID和用户ID查询关联记录是否存在
   *
   * @param activityId 商业活动ID
   * @param userId 用户ID
   * @return 如果存在关联记录则返回1，否则返回0
   */
  int existsByActivityIdAndUserId(
      @Param("activityId") Long activityId, @Param("userId") Long userId);

  /**
   * 根据商业活动ID和用户ID删除关联记录
   *
   * @param activityId 商业活动ID
   * @param userId 用户ID
   */
  void deleteByActivityIdAndUserId(
      @Param("activityId") Long activityId, @Param("userId") Long userId);

  /**
   * 批量查询活动的用户关联记录
   *
   * @param activityIds 活动ID列表
   * @return 用户关联记录列表
   */
  List<BusinessActivityUserEntity> selectByActivityIds(
      @Param("activityIds") List<Long> activityIds);

  void deleteByActivityId(@Param("activityId") Long activityId);

  /**
   * 一次 IN 查询某商业活动已存在的用户关联（A-8 集合化：替代逐个 exists 的 check-then-act 循环）
   *
   * @param activityId 商业活动ID
   * @param userIds 待判定的用户ID集合（可为请求内重复 id，去重由调用方负责）
   * @return 已存在的用户ID集合
   */
  Set<Long> selectExistingUserIds(
      @Param("activityId") Long activityId, @Param("userIds") Collection<Long> userIds);

  /**
   * 按商业活动ID批量删除多个用户关联（单条 delete + IN）
   *
   * @param activityId 商业活动ID
   * @param userIds 用户ID集合
   * @return 删除行数
   */
  int deleteByActivityIdAndUserIds(
      @Param("activityId") Long activityId, @Param("userIds") Collection<Long> userIds);

  /**
   * 多值 INSERT 批量新增用户关联（create_time 走库默认 CURRENT_TIMESTAMP）
   *
   * @param entities 关联记录列表
   * @return 插入行数
   */
  int batchInsert(@Param("entities") List<BusinessActivityUserEntity> entities);
}
