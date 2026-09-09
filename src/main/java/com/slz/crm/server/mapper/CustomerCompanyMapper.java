package com.slz.crm.server.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.slz.crm.pojo.dto.CustomerCompanyDTO;
import com.slz.crm.pojo.entity.CustomerCompanyEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Set;

@Mapper
public interface CustomerCompanyMapper extends BaseMapper<CustomerCompanyEntity> {


     CustomerCompanyEntity getCompanyByContactId(Long id);


     @Select("select id from customer_company where company_name like CONCAT('%', #{companyName}, '%')")
     Set<Long> selectCompanyIdsByName(String companyName);


}
