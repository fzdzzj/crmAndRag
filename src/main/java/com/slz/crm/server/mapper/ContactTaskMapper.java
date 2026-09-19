package com.slz.crm.server.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.slz.crm.pojo.entity.ContactTaskEntity;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ContactTaskMapper extends BaseMapper<ContactTaskEntity> {

  void deleteCommentByTaskIds(@Param("taskIds") List<Long> taskIds);
}
