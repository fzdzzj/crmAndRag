package com.slz.crm.platform.config.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.slz.crm.platform.config.entity.DynamicConfigItemEntity;
import org.apache.ibatis.annotations.Mapper;

/** 动态配置项 Mapper（BaseMapper 即可覆盖本域全部查询； 软删过滤与乐观版本更新由服务层用 Wrapper 显式表达，便于审查）。 */
@Mapper
public interface DynamicConfigItemMapper extends BaseMapper<DynamicConfigItemEntity> {}
