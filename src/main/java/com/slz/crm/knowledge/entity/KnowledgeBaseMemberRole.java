package com.slz.crm.knowledge.entity;

import com.baomidou.mybatisplus.annotation.EnumValue;

/** 知识库成员角色。 */
public enum KnowledgeBaseMemberRole {
  /** 负责人角色，可管理内容和成员。 */
  OWNER,
  /** 编辑角色，可上传和删除内容。 */
  EDITOR,
  /** 只读角色。 */
  READER;

  /** MyBatis-Plus 使用枚举名字稳定入库。 */
  @EnumValue private final String code = name();
}
