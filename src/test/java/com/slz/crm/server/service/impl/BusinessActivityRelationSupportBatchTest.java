package com.slz.crm.server.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.slz.crm.common.enumeration.ErrorCode;
import com.slz.crm.common.exiception.BaseException;
import com.slz.crm.pojo.dto.AddActivityContactRequestDTO;
import com.slz.crm.pojo.dto.AddActivityUserRequestDTO;
import com.slz.crm.pojo.dto.BusinessActivityContactDTO;
import com.slz.crm.pojo.dto.BusinessActivityDTO;
import com.slz.crm.pojo.dto.BusinessActivityUserDTO;
import com.slz.crm.pojo.entity.BusinessActivityContactEntity;
import com.slz.crm.pojo.entity.BusinessActivityEntity;
import com.slz.crm.pojo.entity.BusinessActivityUserEntity;
import com.slz.crm.server.constant.MessageConstant;
import com.slz.crm.server.mapper.BusinessActivityContactMapper;
import com.slz.crm.server.mapper.BusinessActivityUserMapper;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 活动关联维护集合化单测（fix-engineering-debt-loop-write-batch 任务 2.3）。
 *
 * <p>锁定 A-8：8 处「逐条 exists + 逐条写」循环改为「一次 IN 取已存在集合 + list-vs-set 比对 + 批量写」。逐方法断言 四类语义：add 重复 →
 * {@code PARAM_FORMAT_ERROR}(50002) 同文案；delete 缺失 → {@code *_NOT_EXISTS} 同码同文案；null 项跳过保留；请求内重复 id
 * 旧语义保留（add 第二轮抛重复 / delete 第二轮抛不存在）。同时断言批量写形态： 存在性查询恰一次、多值 insert / 单条 IN delete 恰一次、逐条
 * exists/insert/delete 零调用。
 */
@DisplayName("商业活动关联维护集合化（A-8）")
class BusinessActivityRelationSupportBatchTest {

  private static final long ACTIVITY_ID = 11L;
  private static final long CURRENT_ID = 1L;
  private static final long OTHER_ID = 9L;

  // ============================ applyActivityUserRelations ============================

  @Test
  @DisplayName("applyActivityUserRelations add：一次 IN + 一次多值 insert，逐条 exists/insert 零调用")
  @SuppressWarnings("unchecked")
  void applyUserRelations_add_batchShape() {
    BusinessActivityUserMapper mapper = mock(BusinessActivityUserMapper.class);
    when(mapper.selectExistingUserIds(anyLong(), any())).thenReturn(Set.of());
    BusinessActivityDTO dto = new BusinessActivityDTO();
    dto.setAddUser(true);
    dto.setUserIdList(List.of(userDto(7L), userDto(8L)));

    BusinessActivityRelationSupport.applyActivityUserRelations(dto, activity(), CURRENT_ID, mapper);

    verify(mapper, times(1)).selectExistingUserIds(anyLong(), any());
    ArgumentCaptor<List<BusinessActivityUserEntity>> inserted = ArgumentCaptor.forClass(List.class);
    verify(mapper, times(1)).batchInsert(inserted.capture());
    assertEquals(2, inserted.getValue().size());
    assertEquals(Long.valueOf(ACTIVITY_ID), inserted.getValue().get(0).getActivityId());
    assertEquals(Long.valueOf(CURRENT_ID), inserted.getValue().get(0).getCreatorId());
    verify(mapper, never()).existsByActivityIdAndUserId(anyLong(), anyLong());
    verify(mapper, never()).insert(any());
  }

  @Test
  @DisplayName("applyActivityUserRelations add：库内已存在 → 50002 同文案，零写入")
  void applyUserRelations_add_duplicateInDb() {
    BusinessActivityUserMapper mapper = mock(BusinessActivityUserMapper.class);
    when(mapper.selectExistingUserIds(anyLong(), any())).thenReturn(Set.of(7L));
    BusinessActivityDTO dto = new BusinessActivityDTO();
    dto.setAddUser(true);
    dto.setUserIdList(List.of(userDto(7L)));

    assertBizError(
        () ->
            BusinessActivityRelationSupport.applyActivityUserRelations(
                dto, activity(), CURRENT_ID, mapper),
        ErrorCode.PARAM_FORMAT_ERROR,
        MessageConstant.BUSINESS_ACTIVITY_USER_DUPLICATE);
    verify(mapper, never()).batchInsert(any());
  }

  @Test
  @DisplayName("applyActivityUserRelations add：请求内重复 id → 50002（旧语义第二轮 check 命中），零写入")
  void applyUserRelations_add_duplicateInRequest() {
    BusinessActivityUserMapper mapper = mock(BusinessActivityUserMapper.class);
    when(mapper.selectExistingUserIds(anyLong(), any())).thenReturn(Set.of());
    BusinessActivityDTO dto = new BusinessActivityDTO();
    dto.setAddUser(true);
    dto.setUserIdList(List.of(userDto(7L), userDto(7L)));

    assertBizError(
        () ->
            BusinessActivityRelationSupport.applyActivityUserRelations(
                dto, activity(), CURRENT_ID, mapper),
        ErrorCode.PARAM_FORMAT_ERROR,
        MessageConstant.BUSINESS_ACTIVITY_USER_DUPLICATE);
    verify(mapper, never()).batchInsert(any());
  }

  @Test
  @DisplayName("applyActivityUserRelations delete：一次 IN + 单条 IN delete，逐条 delete 零调用")
  void applyUserRelations_delete_batchShape() {
    BusinessActivityUserMapper mapper = mock(BusinessActivityUserMapper.class);
    when(mapper.selectExistingUserIds(anyLong(), any())).thenReturn(Set.of(7L, 8L));
    BusinessActivityDTO dto = new BusinessActivityDTO();
    dto.setAddUser(false);
    dto.setUserIdList(List.of(userDto(7L), userDto(8L)));

    BusinessActivityRelationSupport.applyActivityUserRelations(dto, activity(), CURRENT_ID, mapper);

    verify(mapper, times(1)).selectExistingUserIds(anyLong(), any());
    verify(mapper, times(1)).deleteByActivityIdAndUserIds(ACTIVITY_ID, List.of(7L, 8L));
    verify(mapper, never()).deleteByActivityIdAndUserId(anyLong(), anyLong());
  }

  @Test
  @DisplayName("applyActivityUserRelations delete：任一缺失 → 92015 同文案，零删除")
  void applyUserRelations_delete_missing() {
    BusinessActivityUserMapper mapper = mock(BusinessActivityUserMapper.class);
    when(mapper.selectExistingUserIds(anyLong(), any())).thenReturn(Set.of(7L));
    BusinessActivityDTO dto = new BusinessActivityDTO();
    dto.setAddUser(false);
    dto.setUserIdList(List.of(userDto(7L), userDto(8L)));

    assertBizError(
        () ->
            BusinessActivityRelationSupport.applyActivityUserRelations(
                dto, activity(), CURRENT_ID, mapper),
        ErrorCode.BUSINESS_ACTIVITY_USER_NOT_EXISTS,
        MessageConstant.BUSINESS_ACTIVITY_USER_NOT_EXIST);
    verify(mapper, never()).deleteByActivityIdAndUserIds(anyLong(), any());
  }

  @Test
  @DisplayName("applyActivityUserRelations delete：请求内重复 id → 92015（旧语义第二轮已删行）")
  void applyUserRelations_delete_duplicateInRequest() {
    BusinessActivityUserMapper mapper = mock(BusinessActivityUserMapper.class);
    when(mapper.selectExistingUserIds(anyLong(), any())).thenReturn(Set.of(7L));
    BusinessActivityDTO dto = new BusinessActivityDTO();
    dto.setAddUser(false);
    dto.setUserIdList(List.of(userDto(7L), userDto(7L)));

    assertBizError(
        () ->
            BusinessActivityRelationSupport.applyActivityUserRelations(
                dto, activity(), CURRENT_ID, mapper),
        ErrorCode.BUSINESS_ACTIVITY_USER_NOT_EXISTS,
        MessageConstant.BUSINESS_ACTIVITY_USER_NOT_EXIST);
    verify(mapper, never()).deleteByActivityIdAndUserIds(anyLong(), any());
  }

  // ========================== applyActivityContactRelations ==========================

  @Test
  @DisplayName("applyActivityContactRelations add：一次 IN + 一次多值 insert")
  @SuppressWarnings("unchecked")
  void applyContactRelations_add_batchShape() {
    BusinessActivityContactMapper mapper = mock(BusinessActivityContactMapper.class);
    when(mapper.selectExistingContactIds(anyLong(), any())).thenReturn(Set.of());
    BusinessActivityDTO dto = new BusinessActivityDTO();
    dto.setAddContact(true);
    dto.setContactIdList(List.of(contactDto(7L), contactDto(8L)));

    BusinessActivityRelationSupport.applyActivityContactRelations(
        dto, activity(), CURRENT_ID, mapper);

    ArgumentCaptor<List<BusinessActivityContactEntity>> inserted =
        ArgumentCaptor.forClass(List.class);
    verify(mapper, times(1)).selectExistingContactIds(anyLong(), any());
    verify(mapper, times(1)).batchInsert(inserted.capture());
    assertEquals(2, inserted.getValue().size());
    verify(mapper, never()).existsByActivityIdAndContactId(anyLong(), anyLong());
    verify(mapper, never()).insert(any());
  }

  @Test
  @DisplayName("applyActivityContactRelations add：库内已存在 → 50002 同文案")
  void applyContactRelations_add_duplicateInDb() {
    BusinessActivityContactMapper mapper = mock(BusinessActivityContactMapper.class);
    when(mapper.selectExistingContactIds(anyLong(), any())).thenReturn(Set.of(7L));
    BusinessActivityDTO dto = new BusinessActivityDTO();
    dto.setAddContact(true);
    dto.setContactIdList(List.of(contactDto(7L)));

    assertBizError(
        () ->
            BusinessActivityRelationSupport.applyActivityContactRelations(
                dto, activity(), CURRENT_ID, mapper),
        ErrorCode.PARAM_FORMAT_ERROR,
        MessageConstant.BUSINESS_ACTIVITY_CONTACT_ALREADY_EXIST);
    verify(mapper, never()).batchInsert(any());
  }

  @Test
  @DisplayName("applyActivityContactRelations add：请求内重复 id → 50002")
  void applyContactRelations_add_duplicateInRequest() {
    BusinessActivityContactMapper mapper = mock(BusinessActivityContactMapper.class);
    when(mapper.selectExistingContactIds(anyLong(), any())).thenReturn(Set.of());
    BusinessActivityDTO dto = new BusinessActivityDTO();
    dto.setAddContact(true);
    dto.setContactIdList(List.of(contactDto(7L), contactDto(7L)));

    assertBizError(
        () ->
            BusinessActivityRelationSupport.applyActivityContactRelations(
                dto, activity(), CURRENT_ID, mapper),
        ErrorCode.PARAM_FORMAT_ERROR,
        MessageConstant.BUSINESS_ACTIVITY_CONTACT_ALREADY_EXIST);
    verify(mapper, never()).batchInsert(any());
  }

  @Test
  @DisplayName("applyActivityContactRelations delete：一次 IN + 单条 IN delete")
  void applyContactRelations_delete_batchShape() {
    BusinessActivityContactMapper mapper = mock(BusinessActivityContactMapper.class);
    when(mapper.selectExistingContactIds(anyLong(), any())).thenReturn(Set.of(7L, 8L));
    BusinessActivityDTO dto = new BusinessActivityDTO();
    dto.setAddContact(false);
    dto.setContactIdList(List.of(contactDto(7L), contactDto(8L)));

    BusinessActivityRelationSupport.applyActivityContactRelations(
        dto, activity(), CURRENT_ID, mapper);

    verify(mapper, times(1)).deleteByActivityIdAndContactIds(ACTIVITY_ID, List.of(7L, 8L));
    verify(mapper, never()).deleteByActivityIdAndContactId(anyLong(), anyLong());
  }

  @Test
  @DisplayName("applyActivityContactRelations delete：任一缺失 → 92014 同文案")
  void applyContactRelations_delete_missing() {
    BusinessActivityContactMapper mapper = mock(BusinessActivityContactMapper.class);
    when(mapper.selectExistingContactIds(anyLong(), any())).thenReturn(Set.of(7L));
    BusinessActivityDTO dto = new BusinessActivityDTO();
    dto.setAddContact(false);
    dto.setContactIdList(List.of(contactDto(7L), contactDto(OTHER_ID)));

    assertBizError(
        () ->
            BusinessActivityRelationSupport.applyActivityContactRelations(
                dto, activity(), CURRENT_ID, mapper),
        ErrorCode.BUSINESS_ACTIVITY_CONTACT_NOT_EXISTS,
        MessageConstant.BUSINESS_ACTIVITY_CONTACT_NOT_EXIST);
    verify(mapper, never()).deleteByActivityIdAndContactIds(anyLong(), any());
  }

  @Test
  @DisplayName("applyActivityContactRelations delete：请求内重复 id → 92014")
  void applyContactRelations_delete_duplicateInRequest() {
    BusinessActivityContactMapper mapper = mock(BusinessActivityContactMapper.class);
    when(mapper.selectExistingContactIds(anyLong(), any())).thenReturn(Set.of(7L));
    BusinessActivityDTO dto = new BusinessActivityDTO();
    dto.setAddContact(false);
    dto.setContactIdList(List.of(contactDto(7L), contactDto(7L)));

    assertBizError(
        () ->
            BusinessActivityRelationSupport.applyActivityContactRelations(
                dto, activity(), CURRENT_ID, mapper),
        ErrorCode.BUSINESS_ACTIVITY_CONTACT_NOT_EXISTS,
        MessageConstant.BUSINESS_ACTIVITY_CONTACT_NOT_EXIST);
    verify(mapper, never()).deleteByActivityIdAndContactIds(anyLong(), any());
  }

  // ============================== addContactRelations ==============================

  @Test
  @DisplayName("addContactRelations：null 项跳过保留，非空项一次 IN + 一次多值 insert")
  @SuppressWarnings("unchecked")
  void addContactRelations_nullSkipped_batchShape() {
    BusinessActivityContactMapper mapper = mock(BusinessActivityContactMapper.class);
    when(mapper.selectExistingContactIds(anyLong(), any())).thenReturn(Set.of());
    List<AddActivityContactRequestDTO> requests =
        Arrays.asList(contactRequest(null), contactRequest(7L), contactRequest(8L));

    BusinessActivityRelationSupport.addContactRelations(ACTIVITY_ID, requests, CURRENT_ID, mapper);

    verify(mapper, times(1)).selectExistingContactIds(ACTIVITY_ID, List.of(7L, 8L));
    ArgumentCaptor<List<BusinessActivityContactEntity>> inserted =
        ArgumentCaptor.forClass(List.class);
    verify(mapper, times(1)).batchInsert(inserted.capture());
    assertEquals(2, inserted.getValue().size(), "null 项不得进入批量写");
    assertEquals(Long.valueOf(ACTIVITY_ID), inserted.getValue().get(0).getActivityId());
    verify(mapper, never()).existsByActivityIdAndContactId(anyLong(), anyLong());
    verify(mapper, never()).insert(any());
  }

  @Test
  @DisplayName("addContactRelations：全部 null → 零数据库交互")
  void addContactRelations_allNull_noDatabaseAccess() {
    BusinessActivityContactMapper mapper = mock(BusinessActivityContactMapper.class);

    BusinessActivityRelationSupport.addContactRelations(
        ACTIVITY_ID, List.of(contactRequest(null)), CURRENT_ID, mapper);

    verifyNoInteractions(mapper);
  }

  @Test
  @DisplayName("addContactRelations：库内已存在 → 50002 同文案，零写入")
  void addContactRelations_duplicateInDb() {
    BusinessActivityContactMapper mapper = mock(BusinessActivityContactMapper.class);
    when(mapper.selectExistingContactIds(anyLong(), any())).thenReturn(Set.of(7L));

    assertBizError(
        () ->
            BusinessActivityRelationSupport.addContactRelations(
                ACTIVITY_ID, List.of(contactRequest(7L)), CURRENT_ID, mapper),
        ErrorCode.PARAM_FORMAT_ERROR,
        MessageConstant.BUSINESS_ACTIVITY_CONTACT_ALREADY_EXIST);
    verify(mapper, never()).batchInsert(any());
  }

  @Test
  @DisplayName("addContactRelations：请求内重复 id → 50002，零写入")
  void addContactRelations_duplicateInRequest() {
    BusinessActivityContactMapper mapper = mock(BusinessActivityContactMapper.class);
    when(mapper.selectExistingContactIds(anyLong(), any())).thenReturn(Set.of());

    assertBizError(
        () ->
            BusinessActivityRelationSupport.addContactRelations(
                ACTIVITY_ID, List.of(contactRequest(7L), contactRequest(7L)), CURRENT_ID, mapper),
        ErrorCode.PARAM_FORMAT_ERROR,
        MessageConstant.BUSINESS_ACTIVITY_CONTACT_ALREADY_EXIST);
    verify(mapper, never()).batchInsert(any());
  }

  // =============================== addUserRelations ===============================

  @Test
  @DisplayName("addUserRelations：null 项跳过保留，非空项一次 IN + 一次多值 insert")
  @SuppressWarnings("unchecked")
  void addUserRelations_nullSkipped_batchShape() {
    BusinessActivityUserMapper mapper = mock(BusinessActivityUserMapper.class);
    when(mapper.selectExistingUserIds(anyLong(), any())).thenReturn(Set.of());
    List<AddActivityUserRequestDTO> requests =
        Arrays.asList(userRequest(null), userRequest(7L), userRequest(8L));

    BusinessActivityRelationSupport.addUserRelations(ACTIVITY_ID, requests, CURRENT_ID, mapper);

    verify(mapper, times(1)).selectExistingUserIds(ACTIVITY_ID, List.of(7L, 8L));
    ArgumentCaptor<List<BusinessActivityUserEntity>> inserted = ArgumentCaptor.forClass(List.class);
    verify(mapper, times(1)).batchInsert(inserted.capture());
    assertEquals(2, inserted.getValue().size(), "null 项不得进入批量写");
    assertEquals(Long.valueOf(CURRENT_ID), inserted.getValue().get(0).getCreatorId());
    verify(mapper, never()).existsByActivityIdAndUserId(anyLong(), anyLong());
    verify(mapper, never()).insert(any());
  }

  @Test
  @DisplayName("addUserRelations：全部 null → 零数据库交互")
  void addUserRelations_allNull_noDatabaseAccess() {
    BusinessActivityUserMapper mapper = mock(BusinessActivityUserMapper.class);

    BusinessActivityRelationSupport.addUserRelations(
        ACTIVITY_ID, List.of(userRequest(null)), CURRENT_ID, mapper);

    verifyNoInteractions(mapper);
  }

  @Test
  @DisplayName("addUserRelations：库内已存在 → 50002 同文案，零写入")
  void addUserRelations_duplicateInDb() {
    BusinessActivityUserMapper mapper = mock(BusinessActivityUserMapper.class);
    when(mapper.selectExistingUserIds(anyLong(), any())).thenReturn(Set.of(7L));

    assertBizError(
        () ->
            BusinessActivityRelationSupport.addUserRelations(
                ACTIVITY_ID, List.of(userRequest(7L)), CURRENT_ID, mapper),
        ErrorCode.PARAM_FORMAT_ERROR,
        MessageConstant.BUSINESS_ACTIVITY_USER_DUPLICATE);
    verify(mapper, never()).batchInsert(any());
  }

  @Test
  @DisplayName("addUserRelations：请求内重复 id → 50002，零写入")
  void addUserRelations_duplicateInRequest() {
    BusinessActivityUserMapper mapper = mock(BusinessActivityUserMapper.class);
    when(mapper.selectExistingUserIds(anyLong(), any())).thenReturn(Set.of());

    assertBizError(
        () ->
            BusinessActivityRelationSupport.addUserRelations(
                ACTIVITY_ID, List.of(userRequest(7L), userRequest(7L)), CURRENT_ID, mapper),
        ErrorCode.PARAM_FORMAT_ERROR,
        MessageConstant.BUSINESS_ACTIVITY_USER_DUPLICATE);
    verify(mapper, never()).batchInsert(any());
  }

  // ====================== deleteActivityContactAssociations ======================

  @Test
  @DisplayName("deleteActivityContactAssociations：一次 IN + 单条 IN delete")
  void deleteContactAssociations_batchShape() {
    BusinessActivityContactMapper mapper = mock(BusinessActivityContactMapper.class);
    when(mapper.selectExistingContactIds(anyLong(), any())).thenReturn(Set.of(7L, 8L));

    BusinessActivityRelationSupport.deleteActivityContactAssociations(
        ACTIVITY_ID, List.of(7L, 8L), mapper);

    verify(mapper, times(1)).selectExistingContactIds(ACTIVITY_ID, List.of(7L, 8L));
    verify(mapper, times(1)).deleteByActivityIdAndContactIds(ACTIVITY_ID, List.of(7L, 8L));
    verify(mapper, never()).existsByActivityIdAndContactId(anyLong(), anyLong());
    verify(mapper, never()).deleteByActivityIdAndContactId(anyLong(), anyLong());
  }

  @Test
  @DisplayName("deleteActivityContactAssociations：缺失 → 92014 同文案，零删除")
  void deleteContactAssociations_missing() {
    BusinessActivityContactMapper mapper = mock(BusinessActivityContactMapper.class);
    when(mapper.selectExistingContactIds(anyLong(), any())).thenReturn(Set.of(7L));

    assertBizError(
        () ->
            BusinessActivityRelationSupport.deleteActivityContactAssociations(
                ACTIVITY_ID, List.of(7L, OTHER_ID), mapper),
        ErrorCode.BUSINESS_ACTIVITY_CONTACT_NOT_EXISTS,
        MessageConstant.BUSINESS_ACTIVITY_CONTACT_NOT_EXIST);
    verify(mapper, never()).deleteByActivityIdAndContactIds(anyLong(), any());
  }

  @Test
  @DisplayName("deleteActivityContactAssociations：请求内重复 id → 92014，零删除")
  void deleteContactAssociations_duplicateInRequest() {
    BusinessActivityContactMapper mapper = mock(BusinessActivityContactMapper.class);
    when(mapper.selectExistingContactIds(anyLong(), any())).thenReturn(Set.of(7L));

    assertBizError(
        () ->
            BusinessActivityRelationSupport.deleteActivityContactAssociations(
                ACTIVITY_ID, List.of(7L, 7L), mapper),
        ErrorCode.BUSINESS_ACTIVITY_CONTACT_NOT_EXISTS,
        MessageConstant.BUSINESS_ACTIVITY_CONTACT_NOT_EXIST);
    verify(mapper, never()).deleteByActivityIdAndContactIds(anyLong(), any());
  }

  @Test
  @DisplayName("deleteActivityContactAssociations：null / 空入参 → 零数据库交互")
  void deleteContactAssociations_empty_noDatabaseAccess() {
    BusinessActivityContactMapper mapper = mock(BusinessActivityContactMapper.class);

    BusinessActivityRelationSupport.deleteActivityContactAssociations(ACTIVITY_ID, null, mapper);
    BusinessActivityRelationSupport.deleteActivityContactAssociations(
        ACTIVITY_ID, new ArrayList<>(), mapper);

    verifyNoInteractions(mapper);
  }

  // ======================= deleteActivityUserAssociations =======================

  @Test
  @DisplayName("deleteActivityUserAssociations：一次 IN + 单条 IN delete")
  void deleteUserAssociations_batchShape() {
    BusinessActivityUserMapper mapper = mock(BusinessActivityUserMapper.class);
    when(mapper.selectExistingUserIds(anyLong(), any())).thenReturn(Set.of(7L, 8L));

    BusinessActivityRelationSupport.deleteActivityUserAssociations(
        ACTIVITY_ID, List.of(7L, 8L), mapper);

    verify(mapper, times(1)).selectExistingUserIds(ACTIVITY_ID, List.of(7L, 8L));
    verify(mapper, times(1)).deleteByActivityIdAndUserIds(ACTIVITY_ID, List.of(7L, 8L));
    verify(mapper, never()).existsByActivityIdAndUserId(anyLong(), anyLong());
    verify(mapper, never()).deleteByActivityIdAndUserId(anyLong(), anyLong());
  }

  @Test
  @DisplayName("deleteActivityUserAssociations：缺失 → 92015 同文案，零删除")
  void deleteUserAssociations_missing() {
    BusinessActivityUserMapper mapper = mock(BusinessActivityUserMapper.class);
    when(mapper.selectExistingUserIds(anyLong(), any())).thenReturn(Set.of(7L));

    assertBizError(
        () ->
            BusinessActivityRelationSupport.deleteActivityUserAssociations(
                ACTIVITY_ID, List.of(7L, OTHER_ID), mapper),
        ErrorCode.BUSINESS_ACTIVITY_USER_NOT_EXISTS,
        MessageConstant.BUSINESS_ACTIVITY_USER_NOT_EXIST);
    verify(mapper, never()).deleteByActivityIdAndUserIds(anyLong(), any());
  }

  @Test
  @DisplayName("deleteActivityUserAssociations：请求内重复 id → 92015，零删除")
  void deleteUserAssociations_duplicateInRequest() {
    BusinessActivityUserMapper mapper = mock(BusinessActivityUserMapper.class);
    when(mapper.selectExistingUserIds(anyLong(), any())).thenReturn(Set.of(7L));

    assertBizError(
        () ->
            BusinessActivityRelationSupport.deleteActivityUserAssociations(
                ACTIVITY_ID, List.of(7L, 7L), mapper),
        ErrorCode.BUSINESS_ACTIVITY_USER_NOT_EXISTS,
        MessageConstant.BUSINESS_ACTIVITY_USER_NOT_EXIST);
    verify(mapper, never()).deleteByActivityIdAndUserIds(anyLong(), any());
  }

  @Test
  @DisplayName("deleteActivityUserAssociations：null / 空入参 → 零数据库交互")
  void deleteUserAssociations_empty_noDatabaseAccess() {
    BusinessActivityUserMapper mapper = mock(BusinessActivityUserMapper.class);

    BusinessActivityRelationSupport.deleteActivityUserAssociations(ACTIVITY_ID, null, mapper);
    BusinessActivityRelationSupport.deleteActivityUserAssociations(
        ACTIVITY_ID, new ArrayList<>(), mapper);

    verifyNoInteractions(mapper);
  }

  // =================================== 辅助 ===================================

  private static void assertBizError(
      Runnable action, ErrorCode expectedCode, String expectedMessage) {
    BaseException ex = assertThrows(BaseException.class, action::run);
    assertEquals(expectedCode.getCode(), ex.getCode());
    assertEquals(expectedMessage, ex.getMessage());
  }

  private static BusinessActivityEntity activity() {
    BusinessActivityEntity entity = new BusinessActivityEntity();
    entity.setId(ACTIVITY_ID);
    return entity;
  }

  private static BusinessActivityUserDTO userDto(Long userId) {
    BusinessActivityUserDTO dto = new BusinessActivityUserDTO();
    dto.setUserId(userId);
    return dto;
  }

  private static BusinessActivityContactDTO contactDto(Long contactId) {
    BusinessActivityContactDTO dto = new BusinessActivityContactDTO();
    dto.setContactId(contactId);
    return dto;
  }

  private static AddActivityContactRequestDTO contactRequest(Long contactId) {
    AddActivityContactRequestDTO dto = new AddActivityContactRequestDTO();
    dto.setContactId(contactId);
    dto.setContactRole("参会人");
    return dto;
  }

  private static AddActivityUserRequestDTO userRequest(Long userId) {
    AddActivityUserRequestDTO dto = new AddActivityUserRequestDTO();
    dto.setUserId(userId);
    dto.setUserRole("主持人");
    return dto;
  }
}
