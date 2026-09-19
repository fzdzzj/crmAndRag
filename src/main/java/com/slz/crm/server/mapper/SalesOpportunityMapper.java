package com.slz.crm.server.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.slz.crm.pojo.entity.SalesOpportunityEntity;
import java.time.LocalDateTime;
import java.util.Set;
import org.apache.ibatis.annotations.*;

/** 销售机会Mapper */
@Mapper
public interface SalesOpportunityMapper extends BaseMapper<SalesOpportunityEntity> {
  /** 锁定商机行，保证“检查待审批记录 + 新建审批记录”在同一商机上串行执行。 */
  @Select("SELECT * FROM sales_opportunity WHERE id = #{id} FOR UPDATE")
  SalesOpportunityEntity selectByIdForUpdate(@Param("id") Long id);

  /**
   * 逻辑删除销售机会（仅限阶段5的销售机会）
   *
   * @param id 销售机会ID
   * @return 是否删除成功
   */
  @Update(
      "UPDATE sales_opportunity SET is_deleted = 1 WHERE id = #{id} AND stage = 5 AND is_deleted = 0")
  boolean logicalDeleteById(Long id);

  /**
   * 根据名称模糊查询销售机会ID
   *
   * @param opportunityName 销售机会名称
   * @return 销售机会ID集合
   */
  @Select(
      "select id from sales_opportunity where opportunity_name like concat ('%', #{opportunityName}, '%') ")
  Set<Long> selectOpportunityIdsByName(String opportunityName);

  /**
   * 统计商机总数
   *
   * @return 商机总数
   */
  @Select("SELECT COUNT(*) FROM sales_opportunity WHERE is_deleted = 0")
  Long countTotalOpportunities();

  /**
   * 统计新增商机数
   *
   * @param startTime 开始时间
   * @param endTime 结束时间
   * @return 新增商机数
   */
  @Select(
      "SELECT COUNT(*) FROM sales_opportunity WHERE create_time BETWEEN #{startTime} AND #{endTime} AND is_deleted = 0")
  Long countNewOpportunities(
      @Param("startTime") LocalDateTime startTime, @Param("endTime") LocalDateTime endTime);

  /**
   * 统计指定阶段的商机数量
   *
   * @param stage 商机阶段（0-5）
   * @return 该阶段的商机数量
   */
  @Select("SELECT COUNT(*) FROM sales_opportunity WHERE stage = #{stage} AND is_deleted = 0")
  Long countByStage(@Param("stage") int stage);
}
