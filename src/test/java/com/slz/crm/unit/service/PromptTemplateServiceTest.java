package com.slz.crm.unit.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.slz.crm.common.enumeration.ErrorCode;
import com.slz.crm.common.exiception.BaseException;
import com.slz.crm.pojo.dto.PromptTemplateDTO;
import com.slz.crm.pojo.dto.PromptTemplateRenderDTO;
import com.slz.crm.pojo.entity.PromptTemplateEntity;
import com.slz.crm.server.mapper.PromptTemplateHistoryMapper;
import com.slz.crm.server.service.impl.PromptTemplateServiceImpl;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

/** 提示词模板服务单元测试（add-prompt-template-admin 任务 3.1/3.2）。 */
@ExtendWith(MockitoExtension.class)
@DisplayName("提示词模板服务")
class PromptTemplateServiceTest {

  @Mock private PromptTemplateHistoryMapper historyMapper;

  @Spy @InjectMocks private PromptTemplateServiceImpl service;

  @Spy
  private com.fasterxml.jackson.databind.ObjectMapper objectMapper =
      new com.fasterxml.jackson.databind.ObjectMapper();

  // ===== 渲染三态 =====

  @Test
  @DisplayName("渲染：启用模板占位符正常替换并返回版本")
  void renderReplacesPlaceholders() {
    PromptTemplateEntity template =
        template(
            1L,
            "tpl",
            0,
            3,
            "Hello {{name}}，{{role}}",
            "[{\"name\":\"name\",\"required\":true,\"description\":\"名字\"},{\"name\":\"role\",\"required\":true,\"description\":\"角色\"}]");
    doReturn(template).when(service).getOne(any(LambdaQueryWrapper.class));

    PromptTemplateRenderDTO dto = new PromptTemplateRenderDTO();
    dto.setCode("tpl");
    dto.setVariables(Map.of("name", "World", "role", "Admin"));

    var vo = service.render(dto);
    assertEquals("Hello World，Admin", vo.getContent());
    assertEquals(3, vo.getVersion());
    assertEquals("tpl", vo.getCode());
  }

  @Test
  @DisplayName("渲染：required 变量缺失拒绝")
  void renderRejectsMissingRequired() {
    PromptTemplateEntity template =
        template(
            1L,
            "tpl",
            0,
            1,
            "Hello {{name}}",
            "[{\"name\":\"name\",\"required\":true,\"description\":\"名字\"}]");
    doReturn(template).when(service).getOne(any(LambdaQueryWrapper.class));

    PromptTemplateRenderDTO dto = new PromptTemplateRenderDTO();
    dto.setCode("tpl");
    dto.setVariables(Map.of());

    BaseException ex = assertThrows(BaseException.class, () -> service.render(dto));
    assertEquals(ErrorCode.PARAM_REQUIRED.getCode(), ex.getCode());
  }

  @Test
  @DisplayName("渲染：未定义变量提供值拒绝")
  void renderRejectsUndefinedVariable() {
    PromptTemplateEntity template =
        template(
            1L,
            "tpl",
            0,
            1,
            "Hello {{name}}",
            "[{\"name\":\"name\",\"required\":true,\"description\":\"名字\"}]");
    doReturn(template).when(service).getOne(any(LambdaQueryWrapper.class));

    PromptTemplateRenderDTO dto = new PromptTemplateRenderDTO();
    dto.setCode("tpl");
    dto.setVariables(Map.of("name", "World", "extra", "boom"));

    BaseException ex = assertThrows(BaseException.class, () -> service.render(dto));
    assertEquals(ErrorCode.PARAM_FORMAT_ERROR.getCode(), ex.getCode());
  }

  @Test
  @DisplayName("渲染：停用模板拒绝")
  void renderRejectsDisabledTemplate() {
    PromptTemplateEntity template = template(1L, "tpl", 1, 1, "Hello", null);
    // status=1 → 概率停用，不解析变量直接抛停用
    doReturn(template).when(service).getOne(any(LambdaQueryWrapper.class));

    PromptTemplateRenderDTO dto = new PromptTemplateRenderDTO();
    dto.setCode("tpl");
    dto.setVariables(Map.of());

    BaseException ex = assertThrows(BaseException.class, () -> service.render(dto));
    assertEquals(ErrorCode.NOT_ENABLED.getCode(), ex.getCode());
  }

  @Test
  @DisplayName("渲染：模板不存在拒绝")
  void renderRejectsMissingTemplate() {
    doReturn(null).when(service).getOne(any(LambdaQueryWrapper.class));

    PromptTemplateRenderDTO dto = new PromptTemplateRenderDTO();
    dto.setCode("nope");

    BaseException ex = assertThrows(BaseException.class, () -> service.render(dto));
    assertEquals(ErrorCode.ID_NOT_EXISTS.getCode(), ex.getCode());
  }

  // ===== 版本递增与乐观锁 =====

  @Test
  @DisplayName("创建：version=1 且历史落 CREATE")
  void createInitializesVersionAndHistory() {
    doReturn(0L).when(service).count(any(LambdaQueryWrapper.class));
    doReturn(true).when(service).save(any(PromptTemplateEntity.class));

    PromptTemplateDTO dto =
        dtoOf(
            "tpl",
            "名称",
            "Hello {{name}}",
            "[{\"name\":\"name\",\"required\":true,\"description\":\"名字\"}]");
    service.create(dto);

    ArgumentCaptor<PromptTemplateEntity> captor =
        ArgumentCaptor.forClass(PromptTemplateEntity.class);
    verify(service).save(captor.capture());
    assertEquals(1, captor.getValue().getVersion());
    assertEquals(0, captor.getValue().getStatus());
    assertEquals("tpl", captor.getValue().getCode());

    ArgumentCaptor<com.slz.crm.pojo.entity.PromptTemplateHistoryEntity> hc =
        ArgumentCaptor.forClass(com.slz.crm.pojo.entity.PromptTemplateHistoryEntity.class);
    verify(historyMapper).insert(hc.capture());
    assertEquals("CREATE", hc.getValue().getOperation());
    assertEquals(1, hc.getValue().getVersion());
  }

  @Test
  @DisplayName("更新：乐观锁版本冲突拒绝")
  void updateRejectsVersionConflict() {
    PromptTemplateEntity existing = template(1L, "old", 0, 2, "Hello", null);
    doReturn(existing).when(service).getById(1L);

    PromptTemplateDTO dto = dtoOf("tpl", "名称", "Hello {{name}}", null);
    dto.setId(1L);
    dto.setExpectedVersion(1); // 与当前 version=2 不符

    BaseException ex = assertThrows(BaseException.class, () -> service.update(dto));
    assertEquals(ErrorCode.UPDATE_FAILED.getCode(), ex.getCode());
  }

  @Test
  @DisplayName("更新：版本递增 +1 且历史落 UPDATE")
  void updateIncrementsVersionAndHistory() {
    PromptTemplateEntity existing = template(1L, "old", 0, 2, "Hello", null);
    doReturn(existing).when(service).getById(1L);
    doReturn(0L).when(service).count(any(LambdaQueryWrapper.class));
    doReturn(true).when(service).updateById(any(PromptTemplateEntity.class));
    doReturn(1).when(historyMapper).insert(any());

    PromptTemplateDTO dto = dtoOf("tpl", "名称", "Hello World", null);
    dto.setId(1L);
    dto.setExpectedVersion(2);

    Boolean r = service.update(dto);
    assertTrue(r);
    assertEquals(3, existing.getVersion());
    ArgumentCaptor<com.slz.crm.pojo.entity.PromptTemplateHistoryEntity> hc =
        ArgumentCaptor.forClass(com.slz.crm.pojo.entity.PromptTemplateHistoryEntity.class);
    verify(historyMapper).insert(hc.capture());
    assertEquals("UPDATE", hc.getValue().getOperation());
    assertEquals(3, hc.getValue().getVersion());
  }

  // ===== code 唯一 =====

  @Test
  @DisplayName("创建：code 重复拒绝")
  void createRejectsDuplicateCode() {
    doReturn(1L).when(service).count(any(LambdaQueryWrapper.class));

    PromptTemplateDTO dto = dtoOf("dup", "名称", "Hello", null);
    BaseException ex = assertThrows(BaseException.class, () -> service.create(dto));
    assertEquals(ErrorCode.PARAM_FORMAT_ERROR.getCode(), ex.getCode());
  }

  @Test
  @DisplayName("更新：code 与他人重复拒绝")
  void updateRejectsCodeTakenByOther() {
    PromptTemplateEntity existing = template(1L, "a", 0, 1, "Hello", null);
    doReturn(existing).when(service).getById(1L);
    doReturn(1L).when(service).count(any(LambdaQueryWrapper.class));

    PromptTemplateDTO dto = dtoOf("b", "名称", "Hello", null);
    dto.setId(1L);
    dto.setExpectedVersion(1);

    BaseException ex = assertThrows(BaseException.class, () -> service.update(dto));
    assertEquals(ErrorCode.PARAM_FORMAT_ERROR.getCode(), ex.getCode());
  }

  // ===== 坏 JSON =====

  @Test
  @DisplayName("创建：坏 variables JSON 拒绝")
  void createRejectsBadVariablesJson() {
    PromptTemplateDTO dto = dtoOf("tpl", "名称", "Hello", "not-json{{");
    BaseException ex = assertThrows(BaseException.class, () -> service.create(dto));
    assertEquals(ErrorCode.PARAM_FORMAT_ERROR.getCode(), ex.getCode());
  }

  // ===== 启停用不改版本 =====

  @Test
  @DisplayName("启停用：不改 version 且历史落 STATUS")
  void changeStatusKeepsVersionAndWritesStatus() {
    PromptTemplateEntity existing = template(1L, "tpl", 0, 5, "Hello", null);
    doReturn(existing).when(service).getById(1L);
    doReturn(true).when(service).updateById(any(PromptTemplateEntity.class));

    Boolean r = service.changeStatus(1L, 1);
    assertTrue(r);
    assertEquals(1, existing.getStatus());
    assertEquals(5, existing.getVersion()); // 版本不变

    ArgumentCaptor<com.slz.crm.pojo.entity.PromptTemplateHistoryEntity> hc =
        ArgumentCaptor.forClass(com.slz.crm.pojo.entity.PromptTemplateHistoryEntity.class);
    verify(historyMapper).insert(hc.capture());
    assertEquals("STATUS", hc.getValue().getOperation());
    assertEquals(5, hc.getValue().getVersion());
  }

  // ===== 级联删 =====

  @Test
  @DisplayName("删除：先删 history 再删主表")
  void deleteCascadesHistoryBeforeMain() {
    PromptTemplateEntity existing = template(1L, "tpl", 0, 1, "Hello", null);
    doReturn(existing).when(service).getById(1L);
    doReturn(1).when(historyMapper).delete(any(LambdaQueryWrapper.class));
    doReturn(true).when(service).removeById(1L);

    Boolean r = service.delete(1L);
    assertTrue(r);
    verify(historyMapper).delete(any(LambdaQueryWrapper.class));
    verify(service).removeById(1L);
  }

  // ===== 历史查询 =====

  @Test
  @DisplayName("历史：按模板ID查询并按版本倒序")
  void historyReturnsDescByVersion() {
    PromptTemplateEntity existing = template(1L, "tpl", 0, 3, "Hello", null);
    doReturn(existing).when(service).getById(1L);
    doReturn(List.of(history(1L, 1L, 3, "v3"), history(2L, 1L, 2, "v2"), history(3L, 1L, 1, "v1")))
        .when(historyMapper)
        .selectList(any(LambdaQueryWrapper.class));

    var list = service.history(1L);
    assertEquals(3, list.size());
    assertEquals(3, list.get(0).getVersion());
    assertEquals(1, list.get(2).getVersion());
  }

  // ===== 工具 =====

  private PromptTemplateEntity template(
      Long id, String code, Integer status, Integer version, String content, String variables) {
    PromptTemplateEntity e = new PromptTemplateEntity();
    e.setId(id);
    e.setCode(code);
    e.setName("模板" + code);
    e.setContent(content);
    e.setVariables(variables);
    e.setVersion(version);
    e.setStatus(status);
    return e;
  }

  private com.slz.crm.pojo.entity.PromptTemplateHistoryEntity history(
      Long id, Long tplId, Integer ver, String content) {
    com.slz.crm.pojo.entity.PromptTemplateHistoryEntity h =
        new com.slz.crm.pojo.entity.PromptTemplateHistoryEntity();
    h.setId(id);
    h.setTemplateId(tplId);
    h.setVersion(ver);
    h.setContent(content);
    h.setOperation("UPDATE");
    return h;
  }

  private PromptTemplateDTO dtoOf(String code, String name, String content, String variables) {
    PromptTemplateDTO dto = new PromptTemplateDTO();
    dto.setCode(code);
    dto.setName(name);
    dto.setContent(content);
    dto.setVariables(variables);
    return dto;
  }
}
