package com.slz.crm.knowledge.entity;

import com.baomidou.mybatisplus.annotation.EnumValue;

/** 知识库可见范围。 */
public enum KnowledgeBaseVisibility {
  /** 私有知识库只允许负责人、成员或超级管理员访问。 */
  PRIVATE,
  /** 公共知识库所有登录用户可读，但写入仍受成员角色控制。 */
  PUBLIC;

  /** MyBatis-Plus 使用枚举名字稳定入库。 */
  @EnumValue private final String code = name();
}
