package com.slz.crm.integration.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.slz.crm.integration.AbstractMySqlIT;
import com.slz.crm.integration.RoleTokenSupport;
import com.slz.crm.integration.TestRole;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/** 客户公司接口集成测试：鉴权、增删改查正常链路与批量更新的数据校验缺陷。 */
@AutoConfigureMockMvc
@Transactional
@Sql(scripts = "/init_data.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class CustomerCompanyControllerIT extends AbstractMySqlIT {

  @Autowired private MockMvc mockMvc;

  @Test
  @DisplayName("新增公司不填手机号应成功而非手机号格式错误")
  void shouldCreateCompanyWhenPhoneMissing() throws Exception {
    mockMvc
        .perform(
            post("/company")
                .header("token", RoleTokenSupport.tokenOf(TestRole.ADMIN))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"companyName\":\"无电话公司\",\"industry\":\"IT\",\"grade\":2,\"ownerId\":2}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(1));
  }
}
