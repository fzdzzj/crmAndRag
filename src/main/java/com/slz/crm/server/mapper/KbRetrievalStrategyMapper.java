package com.slz.crm.server.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.slz.crm.knowledge.entity.KbRetrievalStrategy;
import org.apache.ibatis.annotations.Mapper;

/**
 * 知识库级检索策略覆盖 Mapper（add-per-kb-retrieval-strategy-override 任务 1.3）。
 *
 * <p>BaseMapper 即可覆盖本域全部查询；软删过滤与乐观版本更新由服务层用 Wrapper 显式表达，便于审查。
 */
@Mapper
public interface KbRetrievalStrategyMapper extends BaseMapper<KbRetrievalStrategy> {}
