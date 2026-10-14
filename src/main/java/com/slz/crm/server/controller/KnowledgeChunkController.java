package com.slz.crm.server.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.slz.crm.common.annotation.RequirePermission;
import com.slz.crm.common.enumeration.DataScopeLevel;
import com.slz.crm.common.enumeration.ErrorCode;
import com.slz.crm.common.enumeration.PermissionOperates;
import com.slz.crm.common.exiception.BaseException;
import com.slz.crm.common.result.Result;
import com.slz.crm.common.untils.BaseUnit;
import com.slz.crm.platform.contract.UserContext;
import com.slz.crm.platform.contract.UserContextHolder;
import com.slz.crm.pojo.ao.RoleAO;
import com.slz.crm.pojo.vo.KnowledgeChunkVO;
import com.slz.crm.server.service.KnowledgeChunkService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 知识库文档切片（chunk）读取 API。
 *
 * <p>add-source-citation-and-dashboard 任务 1.3：RAG 回答来源高亮/跳转的非 admin 普通用户读取端点—— {@code GET
 * /knowledge/documents/{documentId}/chunks}，挂既有 803 {@code AI_CHAT_SESSION}（能发起 AI 会话
 * 即可查看来源原文），服务内再按 documentId 解析所属知识库复用知识库检索授权链双保险（无授权 → 12002）。零新权限常量。
 */
@RestController
@RequestMapping("/knowledge")
@RequiredArgsConstructor
public class KnowledgeChunkController {

  private final KnowledgeChunkService knowledgeChunkService;

  /** 分页读取知识库文档切片（chunkIndex 升序），供前端文档查看抽屉渲染与 excerpt 高亮定位。 */
  @GetMapping("/documents/{documentId}/chunks")
  @RequirePermission(PermissionOperates.AI_CHAT_SESSION)
  public Result<Page<KnowledgeChunkVO>> listChunks(
      @PathVariable("documentId") String documentId,
      @RequestParam(value = "pageNum", required = false) Integer pageNum,
      @RequestParam(value = "pageSize", required = false) Integer pageSize) {
    return Result.success(
        knowledgeChunkService.listChunks(documentId, pageNum, pageSize, currentUser()));
  }

  /**
   * 当前用户上下文（过渡桥，与 {@code KnowledgeAdminService#currentUser()} 口径一致）：优先契约口径 {@link
   * UserContextHolder}，为空时回退 {@link BaseUnit}（RoleAO）构造合法 UserContext；两者皆无视为未登录抛 TOKEN_ERROR。
   */
  private UserContext currentUser() {
    UserContext user = UserContextHolder.current();
    UserContext result;
    if (user != null) {
      result = user;
    } else {
      RoleAO role = BaseUnit.getCurrentRole();
      if (role != null
          && role.getId() != null
          && role.getRoleId() != null
          && role.getDeptId() != null) {
        result =
            new UserContext(
                role.getId(), role.getRoleId(), role.getDeptId(), DataScopeLevel.NONE, null);
      } else {
        throw new BaseException(ErrorCode.TOKEN_ERROR);
      }
    }
    return result;
  }
}
