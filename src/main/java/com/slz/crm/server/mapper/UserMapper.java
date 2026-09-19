package com.slz.crm.server.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.slz.crm.pojo.entity.UserEntity;
import java.util.Set;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface UserMapper extends BaseMapper<UserEntity> {

  @Select("select id from sys_user where real_name like concat ('%', #{userName}, '%') ")
  Set<Long> selectUserIdsByUserName(String userName);
}
