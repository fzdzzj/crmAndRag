package com.slz.crm.server.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.slz.crm.pojo.entity.CustomerContactEntity;
import java.util.Set;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface CustomerContactMapper extends BaseMapper<CustomerContactEntity> {

  @Select("select id from customer_contact where name like CONCAT('%', #{contactName}, '%')")
  Set<Long> selectContactIdsByName(String contactName);
}
