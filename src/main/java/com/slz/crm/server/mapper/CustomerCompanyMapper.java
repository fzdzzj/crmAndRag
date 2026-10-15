package com.slz.crm.server.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.slz.crm.pojo.entity.CustomerCompanyEntity;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface CustomerCompanyMapper extends BaseMapper<CustomerCompanyEntity> {

  CustomerCompanyEntity getCompanyByContactId(Long id);

  @Select(
      "select id from customer_company where company_name like CONCAT('%', #{companyName}, '%')")
  Set<Long> selectCompanyIdsByName(String companyName);

  /**
   * 公海池客户分页查询（add-customer-public-sea-pool 任务 2.1）。
   *
   * <p>绕过 {@code QueryWrapperAspect} 私海 DataScope：方法名不含 list/page/selectList/selectPage 且不接收 {@code
   * Wrapper}，切面点cut不会命中；公海客户面向全体持有查询权限的销售开放，不受私海 creator_id/owner 过滤限制。
   *
   * @param page MyBatis-Plus 分页（自动改写 COUNT 与 LIMIT）
   * @param companyName 公司名（可选模糊匹配，空则忽略）
   * @param industry 行业（可选精确匹配，空则忽略）
   * @param grade 客户等级（可选精确匹配，空则忽略）
   * @return 分页结果（仅 pool_status=1 AND is_deleted=0 的公海客户）
   */
  @Select(
      "<script>"
          + "SELECT * FROM customer_company WHERE pool_status = 1 AND is_deleted = 0"
          + "  <if test='companyName != null and companyName != \"\"'>"
          + "    AND company_name LIKE CONCAT('%', #{companyName}, '%')"
          + "  </if>"
          + "  <if test='industry != null and industry != \"\"'>"
          + "    AND industry = #{industry}"
          + "  </if>"
          + "  <if test='grade != null'>"
          + "    AND grade = #{grade}"
          + "  </if>"
          + " ORDER BY id DESC"
          + "</script>")
  Page<CustomerCompanyEntity> selectPoolPage(
      Page<CustomerCompanyEntity> page,
      @Param("companyName") String companyName,
      @Param("industry") String industry,
      @Param("grade") Integer grade);

  /**
   * 超时自动回收候选客户公司（add-customer-public-sea-pool 任务 3.1）。
   *
   * <p>只筛选私海（pool_status=0）未删除客户：有跟进记录且 beyond 30 天，或无跟进且创建超 15 天。 返回完整实体以便记录前一负责人与批量更新；系统调度执行，专有
   * SQL 不进入 {@code QueryWrapperAspect} 私海 DataScope。
   */
  @Select(
      "SELECT * FROM customer_company"
          + " WHERE pool_status = 0 AND is_deleted = 0"
          + " AND ((last_activity_time IS NOT NULL AND last_activity_time < DATE_SUB(NOW(), INTERVAL 30 DAY))"
          + "   OR (last_activity_time IS NULL AND create_time < DATE_SUB(NOW(), INTERVAL 15 DAY)))")
  List<CustomerCompanyEntity> selectRecyclableCustomers();

  /**
   * 批量刷新客户最近有效跟进时间（add-customer-public-sea-pool 任务 3.2；商业活动保存后事务提交异步联动）。 last_activity_time =
   * NOW()，用于超时自动回收的跟进时效判定。
   *
   * @param companyIds 待刷新客户公司 ID 集合（非空）
   * @return 受影响行数
   */
  @Update(
      "<script>"
          + "UPDATE customer_company SET last_activity_time = NOW()"
          + " WHERE is_deleted = 0 AND id IN "
          + "<foreach collection='companyIds' item='cid' open='(' separator=',' close=')'>"
          + "#{cid}"
          + "</foreach>"
          + "</script>")
  int updateLastActivityTime(@Param("companyIds") Collection<Long> companyIds);
}
