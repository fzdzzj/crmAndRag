package com.slz.crm.server.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.slz.crm.knowledge.entity.KbRetrievalStrategyHistory;
import org.apache.ibatis.annotations.Mapper;

/**
 * 知识库级检索策略版本历史 Mapper（add-per-kb-retrieval-strategy-override 任务 1.3）。
 *
 * <p>BaseMapper 即可覆盖本域全部查询；回滚定位（按 kb_id + strategy_key + version 取 new_value）由服务层用 Wrapper 显式表达。
 */
@Mapper
public interface KbRetrievalStrategyHistoryMapper extends BaseMapper<KbRetrievalStrategyHistory> {}
