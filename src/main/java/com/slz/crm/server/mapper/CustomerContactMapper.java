package com.slz.crm.server.mapper;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.slz.crm.pojo.entity.CustomerContactEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Set;


@Mapper
public interface CustomerContactMapper extends BaseMapper<CustomerContactEntity> {

    @Select("select id from customer_contact where name like CONCAT('%', #{contactName}, '%')")
    Set<Long> selectContactIdsByName(String contactName);

}
