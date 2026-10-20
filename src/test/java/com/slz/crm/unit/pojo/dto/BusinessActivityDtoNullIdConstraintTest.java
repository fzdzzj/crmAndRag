package com.slz.crm.unit.pojo.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.slz.crm.pojo.dto.AddActivityContactRequestDTO;
import com.slz.crm.pojo.dto.AddActivityUserRequestDTO;
import com.slz.crm.pojo.dto.BusinessActivityContactDTO;
import com.slz.crm.pojo.dto.BusinessActivityDTO;
import com.slz.crm.pojo.dto.BusinessActivityUserDTO;
import com.slz.crm.pojo.dto.ValidationGroups;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * 关联维护 5 个 DTO 的身份字段约束（fix-backlog-batch2 ⑧）。
 *
 * <p>直接对打包校验器断言，绕开 MVC 管线：①4 个关联 DTO 的 userId / contactId 为 null 时在 Default 组即产生违例；②{@code
 * BusinessActivityDTO.id} 的必填只在 {@link ValidationGroups.Update} 组生效——创建请求本就不带活动 id，若挂到 Default 组，
 * 所有创建请求都会变成 50002「参数校验失败」。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("关联维护 DTO null-id 约束（@NotNull / Update 分组）")
class BusinessActivityDtoNullIdConstraintTest {

  private ValidatorFactory validatorFactory;

  private Validator validator;

  @BeforeAll
  void setUp() {
    validatorFactory = Validation.buildDefaultValidatorFactory();
    validator = validatorFactory.getValidator();
  }

  @AfterAll
  void tearDown() {
    validatorFactory.close();
  }

  @Test
  @DisplayName("BusinessActivityUserDTO.userId 为 null → 一条违例，文案为业务提示")
  void userDto_nullUserId_violatesDefaultGroup() {
    Set<ConstraintViolation<BusinessActivityUserDTO>> violations =
        validator.validate(new BusinessActivityUserDTO());

    assertThat(violations).hasSize(1);
    assertThat(violations.iterator().next().getMessage()).isEqualTo("用户ID不能为空");
    assertThat(violations.iterator().next().getPropertyPath().toString()).isEqualTo("userId");
  }

  @Test
  @DisplayName("BusinessActivityContactDTO.contactId 为 null → 一条违例，文案为业务提示")
  void contactDto_nullContactId_violatesDefaultGroup() {
    Set<ConstraintViolation<BusinessActivityContactDTO>> violations =
        validator.validate(new BusinessActivityContactDTO());

    assertThat(violations).hasSize(1);
    assertThat(violations.iterator().next().getMessage()).isEqualTo("联系人ID不能为空");
    assertThat(violations.iterator().next().getPropertyPath().toString()).isEqualTo("contactId");
  }

  @Test
  @DisplayName("AddActivityUserRequestDTO.userId 为 null → 一条违例")
  void addUserRequest_nullUserId_violatesDefaultGroup() {
    Set<ConstraintViolation<AddActivityUserRequestDTO>> violations =
        validator.validate(new AddActivityUserRequestDTO());

    assertThat(violations).hasSize(1);
    assertThat(violations.iterator().next().getMessage()).isEqualTo("用户ID不能为空");
  }

  @Test
  @DisplayName("AddActivityContactRequestDTO.contactId 为 null → 一条违例")
  void addContactRequest_nullContactId_violatesDefaultGroup() {
    Set<ConstraintViolation<AddActivityContactRequestDTO>> violations =
        validator.validate(new AddActivityContactRequestDTO());

    assertThat(violations).hasSize(1);
    assertThat(violations.iterator().next().getMessage()).isEqualTo("联系人ID不能为空");
  }

  @Test
  @DisplayName("BusinessActivityDTO.id 为 null → Default 组零违例（创建路径不带 id，不得误拦）")
  void activityDto_nullId_passesDefaultGroup() {
    BusinessActivityDTO dto = new BusinessActivityDTO();
    dto.setActivityTitle("客户拜访");

    assertThat(validator.validate(dto)).isEmpty();
  }

  @Test
  @DisplayName("BusinessActivityDTO.id 为 null → Update 组产生违例；补上 id 后零违例")
  void activityDto_nullId_violatesUpdateGroup() {
    BusinessActivityDTO missingId = new BusinessActivityDTO();
    missingId.setActivityTitle("客户拜访");

    Set<ConstraintViolation<BusinessActivityDTO>> violations =
        validator.validate(missingId, ValidationGroups.Update.class);

    assertThat(violations).hasSize(1);
    assertThat(violations.iterator().next().getMessage()).isEqualTo("活动ID不能为空");
    assertThat(violations.iterator().next().getPropertyPath().toString()).isEqualTo("id");

    BusinessActivityDTO withId = new BusinessActivityDTO();
    withId.setId(11L);
    assertThat(validator.validate(withId, ValidationGroups.Update.class)).isEmpty();
  }

  @Test
  @DisplayName("BusinessActivityDTO 级联：列表元素 userId / contactId 为 null 各产生一条违例")
  void activityDto_cascadesIntoRelationLists() {
    BusinessActivityUserDTO userDTO = new BusinessActivityUserDTO();
    userDTO.setUserRole("主讲人");
    BusinessActivityContactDTO contactDTO = new BusinessActivityContactDTO();
    contactDTO.setContactRole("决策者");
    BusinessActivityDTO dto = new BusinessActivityDTO();
    dto.setActivityTitle("客户拜访");
    dto.setUserIdList(java.util.List.of(userDTO));
    dto.setContactIdList(java.util.List.of(contactDTO));

    assertThat(validator.validate(dto))
        .extracting(violation -> violation.getPropertyPath().toString())
        .containsExactlyInAnyOrder("userIdList[0].userId", "contactIdList[0].contactId");
  }
}
