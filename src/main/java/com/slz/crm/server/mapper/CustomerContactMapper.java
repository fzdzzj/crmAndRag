package com.slz.crm.server.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.slz.crm.pojo.entity.CustomerContactEntity;
import java.util.Collection;
import java.util.Set;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface CustomerContactMapper extends BaseMapper<CustomerContactEntity> {

  @Select("select id from customer_contact where name like CONCAT('%', #{contactName}, '%')")
  Set<Long> selectContactIdsByName(String contactName);

  /** 按联系人 ID 集合反查其所属客户公司 ID（去重；add-customer-public-sea-pool 任务 3.2 跟进活跃度联动源头）。 */
  @Select(
      "<script>"
          + "SELECT DISTINCT company_id FROM customer_contact WHERE company_id IS NOT NULL"
          + " AND id IN "
          + "<foreach collection='contactIds' item='cid' open='(' separator=',' close=')'>"
          + "#{cid}"
          + "</foreach>"
          + "</script>")
  Set<Long> selectCompanyIdsByContactIds(@Param("contactIds") Collection<Long> contactIds);
}
