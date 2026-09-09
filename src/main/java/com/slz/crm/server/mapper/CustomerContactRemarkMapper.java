package com.slz.crm.server.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.slz.crm.pojo.entity.CustomerContactRemarkEntity;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface CustomerContactRemarkMapper extends BaseMapper<CustomerContactRemarkEntity> {

    /**
     * 根据联系人 ID 查询备注列表
     */
    List<CustomerContactRemarkEntity> selectByContactId(@Param("contactId") Long contactId);


    /**
     * 批量插入备注
     * @param entities 备注实体列表
     * @return 影响行数
     */
    Integer insertBatch(@Param("entities") List<CustomerContactRemarkEntity> entities);
}


