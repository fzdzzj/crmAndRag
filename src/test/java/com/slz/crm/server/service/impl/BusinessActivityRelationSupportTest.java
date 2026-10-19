package com.slz.crm.server.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.slz.crm.common.exiception.BaseException;
import com.slz.crm.pojo.dto.AddActivityContactRequestDTO;
import com.slz.crm.pojo.dto.AddActivityUserRequestDTO;
import com.slz.crm.pojo.dto.BusinessActivityContactDTO;
import com.slz.crm.pojo.dto.BusinessActivityDTO;
import com.slz.crm.pojo.dto.BusinessActivityUserDTO;
import com.slz.crm.pojo.entity.BusinessActivityEntity;
import com.slz.crm.server.constant.MessageConstant;
import com.slz.crm.server.mapper.BusinessActivityContactMapper;
import com.slz.crm.server.mapper.BusinessActivityUserMapper;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 返场补丁：原先 4 处「商业活动关联已存在」待裁决单 String 构造，按 Owner 拍板映射为 {@code PARAM_FORMAT_ERROR}(50002) 并保留原文案。
 *
 * <p>断言口径：真实 service 方法路径上抛出的 {@link BaseException} 必须带 {@code code == 50002}，且 {@code
 * getMessage()} 等于原 {@code MessageConstant} 整串（客户端文案与 legacy 完全一致）。
 *
 * <p>fix-engineering-debt-loop-write-batch（A-8）：4 例的断言与文案一字未改，仅把存在性桩点从逐条 {@code
 * existsByActivityIdAnd*Id} 换成集合化后的 {@code selectExisting*Ids}（一次 IN 取已存在集合），继续锁定「重复关联 → 50002 +
 * 原文案」。
 */
@DisplayName("商业活动关联重复校验错误码映射（50002）")
class BusinessActivityRelationSupportTest {

  private static final long ACTIVITY_ID = 11L;
  private static final long CURRENT_ID = 1L;
  private static final long TARGET_ID = 7L;

  @Test
  @DisplayName("applyActivityUserRelations 新增重复关联 → 50002 且保留原文案")
  void applyActivityUserRelations_addDuplicate_mapsTo50002() {
    BusinessActivityUserMapper mapper = mock(BusinessActivityUserMapper.class);
    when(mapper.selectExistingUserIds(anyLong(), any())).thenReturn(Set.of(TARGET_ID));
    BusinessActivityDTO dto = new BusinessActivityDTO();
    dto.setAddUser(true);
    BusinessActivityUserDTO userDTO = new BusinessActivityUserDTO();
    userDTO.setUserId(TARGET_ID);
    dto.setUserIdList(List.of(userDTO));

    assertThatThrownBy(
            () ->
                BusinessActivityRelationSupport.applyActivityUserRelations(
                    dto, activityEntity(), CURRENT_ID, mapper))
        .isInstanceOf(BaseException.class)
        .hasMessage(MessageConstant.BUSINESS_ACTIVITY_USER_DUPLICATE)
        .satisfies(e -> assertThat(((BaseException) e).getCode()).isEqualTo(50002));
  }

  @Test
  @DisplayName("applyActivityContactRelations 新增重复关联 → 50002 且保留原文案")
  void applyActivityContactRelations_addDuplicate_mapsTo50002() {
    BusinessActivityContactMapper mapper = mock(BusinessActivityContactMapper.class);
    when(mapper.selectExistingContactIds(anyLong(), any())).thenReturn(Set.of(TARGET_ID));
    BusinessActivityDTO dto = new BusinessActivityDTO();
    dto.setAddContact(true);
    BusinessActivityContactDTO contactDTO = new BusinessActivityContactDTO();
    contactDTO.setContactId(TARGET_ID);
    dto.setContactIdList(List.of(contactDTO));

    assertThatThrownBy(
            () ->
                BusinessActivityRelationSupport.applyActivityContactRelations(
                    dto, activityEntity(), CURRENT_ID, mapper))
        .isInstanceOf(BaseException.class)
        .hasMessage(MessageConstant.BUSINESS_ACTIVITY_CONTACT_ALREADY_EXIST)
        .satisfies(e -> assertThat(((BaseException) e).getCode()).isEqualTo(50002));
  }

  @Test
  @DisplayName("addContactRelations 已存在关联 → 50002 且保留原文案")
  void addContactRelations_duplicate_mapsTo50002() {
    BusinessActivityContactMapper mapper = mock(BusinessActivityContactMapper.class);
    when(mapper.selectExistingContactIds(anyLong(), any())).thenReturn(Set.of(TARGET_ID));
    AddActivityContactRequestDTO requestDTO = new AddActivityContactRequestDTO();
    requestDTO.setContactId(TARGET_ID);

    assertThatThrownBy(
            () ->
                BusinessActivityRelationSupport.addContactRelations(
                    ACTIVITY_ID, List.of(requestDTO), CURRENT_ID, mapper))
        .isInstanceOf(BaseException.class)
        .hasMessage(MessageConstant.BUSINESS_ACTIVITY_CONTACT_ALREADY_EXIST)
        .satisfies(e -> assertThat(((BaseException) e).getCode()).isEqualTo(50002));
  }

  @Test
  @DisplayName("addUserRelations 已存在关联 → 50002 且保留原文案")
  void addUserRelations_duplicate_mapsTo50002() {
    BusinessActivityUserMapper mapper = mock(BusinessActivityUserMapper.class);
    when(mapper.selectExistingUserIds(anyLong(), any())).thenReturn(Set.of(TARGET_ID));
    AddActivityUserRequestDTO requestDTO = new AddActivityUserRequestDTO();
    requestDTO.setUserId(TARGET_ID);

    assertThatThrownBy(
            () ->
                BusinessActivityRelationSupport.addUserRelations(
                    ACTIVITY_ID, List.of(requestDTO), CURRENT_ID, mapper))
        .isInstanceOf(BaseException.class)
        .hasMessage(MessageConstant.BUSINESS_ACTIVITY_USER_DUPLICATE)
        .satisfies(e -> assertThat(((BaseException) e).getCode()).isEqualTo(50002));
  }

  private static BusinessActivityEntity activityEntity() {
    BusinessActivityEntity entity = new BusinessActivityEntity();
    entity.setId(ACTIVITY_ID);
    return entity;
  }
}
