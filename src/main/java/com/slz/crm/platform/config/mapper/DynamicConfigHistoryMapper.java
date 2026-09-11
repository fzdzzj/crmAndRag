package com.slz.crm.platform.config.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.slz.crm.platform.config.entity.DynamicConfigHistoryEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * 动态配置版本历史 Mapper。
 */
@Mapper
public interface DynamicConfigHistoryMapper extends BaseMapper<DynamicConfigHistoryEntity> {
}
