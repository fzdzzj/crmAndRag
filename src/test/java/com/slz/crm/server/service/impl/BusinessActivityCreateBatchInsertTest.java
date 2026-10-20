package com.slz.crm.server.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.slz.crm.common.untils.BaseUnit;
import com.slz.crm.pojo.ao.RoleAO;
import com.slz.crm.pojo.dto.BusinessActivityContactDTO;
import com.slz.crm.pojo.dto.BusinessActivityDTO;
import com.slz.crm.pojo.dto.BusinessActivityUserDTO;
import com.slz.crm.pojo.entity.BusinessActivityContactEntity;
import com.slz.crm.pojo.entity.BusinessActivityEntity;
import com.slz.crm.pojo.entity.BusinessActivityUserEntity;
import com.slz.crm.server.mapper.BusinessActivityContactMapper;
import com.slz.crm.server.mapper.BusinessActivityMapper;
import com.slz.crm.server.mapper.BusinessActivityUserMapper;
import com.slz.crm.server.mapper.ContactTaskMapper;
import com.slz.crm.server.mapper.CustomerCompanyMapper;
import com.slz.crm.server.mapper.CustomerContactMapper;
import com.slz.crm.server.service.AssistRequestService;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * E-16 专项：BusinessActivityServiceImpl.create 路径的双表批量写入。
 *
 * <p>锁定四件事：联系人表与用户表各恰一次 {@code batchInsert}、单条 {@code insert} 零调用、实体装饰（BeanUtils 拷贝后的业务字段 +
 * creatorId + activityId）与旧 forEach 实现一致、null / 空列表跳过口径保留。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("E-16 活动创建双表批量写入")
class BusinessActivityCreateBatchInsertTest {

  private static final Long CURRENT_USER_ID = 7L;
  private static final Long ACTIVITY_ID = 900L;

  @Mock private BusinessActivityMapper businessActivityMapper;

  @Mock private BusinessActivityContactMapper businessActivityContactMapper;

  @Mock private BusinessActivityUserMapper businessActivityUserMapper;

  @Mock private AssistRequestService assistRequestService;

  @Mock private ContactTaskMapper contactTaskMapper;

  @Mock private CustomerContactMapper customerContactMapper;

  @Mock private CustomerCompanyMapper customerCompanyMapper;

  @Captor private ArgumentCaptor<List<BusinessActivityContactEntity>> contactCaptor;

  @Captor private ArgumentCaptor<List<BusinessActivityUserEntity>> userCaptor;

  private BusinessActivityServiceImpl service;

  @BeforeEach
  void setUp() {
    service = new BusinessActivityServiceImpl();
    ReflectionTestUtils.setField(service, "baseMapper", businessActivityMapper);
    ReflectionTestUtils.setField(service, "businessActivityMapper", businessActivityMapper);
    ReflectionTestUtils.setField(
        service, "businessActivityContactMapper", businessActivityContactMapper);
    ReflectionTestUtils.setField(service, "businessActivityUserMapper", businessActivityUserMapper);
    ReflectionTestUtils.setField(service, "assistRequestService", assistRequestService);
    ReflectionTestUtils.setField(service, "contactTaskMapper", contactTaskMapper);
    ReflectionTestUtils.setField(service, "customerContactMapper", customerContactMapper);
    ReflectionTestUtils.setField(service, "customerCompanyMapper", customerCompanyMapper);
    when(businessActivityMapper.insert(any(BusinessActivityEntity.class)))
        .thenAnswer(
            invocation -> {
              invocation.getArgument(0, BusinessActivityEntity.class).setId(ACTIVITY_ID);
              return 1;
            });
    RoleAO role = new RoleAO();
    role.setId(CURRENT_USER_ID);
    BaseUnit.setCurrentRole(role);
  }

  @AfterEach
  void tearDown() {
    BaseUnit.removeCurrentId();
  }

  @Test
  @DisplayName("1. create：双表各恰一次 batchInsert、单条 insert 零调用、装饰 activityId+creatorId")
  void createBatchInsertsBothRelationTables() {
    BusinessActivityDTO dto = dtoOf();
    dto.setContactIdList(List.of(contact(21L, "负责人"), contact(22L, "参与人")));
    dto.setUserIdList(List.of(user(31L, "协作"), user(32L, "观察")));

    assertTrue(service.create(dto));

    verify(businessActivityContactMapper, times(1)).batchInsert(contactCaptor.capture());
    List<BusinessActivityContactEntity> contacts = contactCaptor.getValue();
    assertEquals(2, contacts.size());
    assertEquals(ACTIVITY_ID, contacts.get(0).getActivityId());
    assertEquals(CURRENT_USER_ID, contacts.get(0).getCreatorId());
    assertEquals(21L, contacts.get(0).getContactId(), "BeanUtils 拷贝的 contactId 保留");
    assertEquals("负责人", contacts.get(0).getContactRole());
    verify(businessActivityContactMapper, never()).insert(any(BusinessActivityContactEntity.class));

    verify(businessActivityUserMapper, times(1)).batchInsert(userCaptor.capture());
    List<BusinessActivityUserEntity> users = userCaptor.getValue();
    assertEquals(2, users.size());
    assertEquals(ACTIVITY_ID, users.get(0).getActivityId());
    assertEquals(CURRENT_USER_ID, users.get(0).getCreatorId());
    assertEquals(31L, users.get(0).getUserId());
    assertEquals("协作", users.get(0).getUserRole());
    verify(businessActivityUserMapper, never()).insert(any(BusinessActivityUserEntity.class));
  }

  @Test
  @DisplayName("2. create：仅联系人列表时用户表零写")
  void createWithContactsOnlySkipsUserBatch() {
    BusinessActivityDTO dto = dtoOf();
    dto.setContactIdList(List.of(contact(21L, "负责人")));
    dto.setUserIdList(null);

    service.create(dto);

    verify(businessActivityContactMapper, times(1)).batchInsert(anyList());
    verify(businessActivityUserMapper, never()).batchInsert(anyList());
  }

  @Test
  @DisplayName("3. create：null 列表跳过口径保留（双表零 batchInsert）")
  void createWithNullListsSkipsBothBatches() {
    service.create(dtoOf());

    verify(businessActivityContactMapper, never()).batchInsert(anyList());
    verify(businessActivityUserMapper, never()).batchInsert(anyList());
  }

  @Test
  @DisplayName("4. create：空列表不产生空 SQL（双表零 batchInsert）")
  void createWithEmptyListsSkipsBothBatches() {
    BusinessActivityDTO dto = dtoOf();
    dto.setContactIdList(List.of());
    dto.setUserIdList(List.of());

    service.create(dto);

    verify(businessActivityContactMapper, never()).batchInsert(anyList());
    verify(businessActivityUserMapper, never()).batchInsert(anyList());
  }

  private BusinessActivityDTO dtoOf() {
    BusinessActivityDTO dto = new BusinessActivityDTO();
    dto.setActivityTitle("E-16 创建批量写");
    return dto;
  }

  private BusinessActivityContactDTO contact(Long contactId, String role) {
    BusinessActivityContactDTO dto = new BusinessActivityContactDTO();
    dto.setContactId(contactId);
    dto.setContactRole(role);
    return dto;
  }

  private BusinessActivityUserDTO user(Long userId, String role) {
    BusinessActivityUserDTO dto = new BusinessActivityUserDTO();
    dto.setUserId(userId);
    dto.setUserRole(role);
    return dto;
  }
}
