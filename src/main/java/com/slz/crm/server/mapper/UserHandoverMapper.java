package com.slz.crm.server.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.slz.crm.pojo.entity.UserHandoverEntity;
import org.apache.ibatis.annotations.Mapper;

/** 用户交接记录 Mapper 接口 */
@Mapper
public interface UserHandoverMapper extends BaseMapper<UserHandoverEntity> {}
