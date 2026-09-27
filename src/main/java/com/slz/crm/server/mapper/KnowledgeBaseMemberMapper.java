package com.slz.crm.server.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.slz.crm.knowledge.entity.KnowledgeBaseMemberEntity;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 知识库成员表 Mapper。 */
@Mapper
public interface KnowledgeBaseMemberMapper extends BaseMapper<KnowledgeBaseMemberEntity> {

  /**
   * 查询该用户有效成员身份所指向的、仍存在且未软删的知识库 ID （fix-knowledge-base-member-visibility-boundary 任务 3.1）。
   *
   * <p>授权边界修正：成员行存活不代表目标库仍有效——库可能已软删，成员行也可能是孤儿引用。本查询 JOIN knowledge_base 把成员来源收敛到「存在且
   * is_deleted=0」的库，一次查询完成、无逐成员查库、无无界 IN， 并按成员行主键顺序返回以保持既有 owner→PUBLIC→member 的相对顺序。
   *
   * @param userId 成员跨域引用 user:&lt;id&gt;
   * @return 有效成员库 ID（按成员行主键升序；无成员返回空列表）
   */
  @Select(
      """
            SELECT m.knowledge_base_id
            FROM knowledge_base_member m
            JOIN knowledge_base kb ON kb.id = m.knowledge_base_id AND kb.is_deleted = 0
            WHERE m.is_deleted = 0
              AND m.user_id = #{userId}
            ORDER BY m.id
            """)
  List<Long> selectActiveKnowledgeBaseIdsByUserId(@Param("userId") String userId);
}
