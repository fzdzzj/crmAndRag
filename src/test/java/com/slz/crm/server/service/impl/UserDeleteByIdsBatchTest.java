package com.slz.crm.server.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.slz.crm.common.enumeration.ErrorCode;
import com.slz.crm.common.exiception.BaseException;
import com.slz.crm.pojo.entity.UserEntity;
import com.slz.crm.server.mapper.UserMapper;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 用户批删单条化 + 置空修复单测（fix-engineering-debt-loop-write-batch 任务 3.3）。
 *
 * <p>锁定 A-10：{@code deleteById(List&lt;Long&gt;)} 由「逐条 updateById」收敛为单条 {@code
 * LambdaUpdateWrapper}（SET status=3 + 显式 set email=null / phone=null + WHERE id IN (...))；预检闸（size
 * 不符抛 {@code DATA_DELETE_FAILED}「删除失败，请注意用户状态」）与重复 id 行为不变。
 *
 * <p>置空新语义断言：实体字段默认 NOT_NULL 更新策略下 {@code updateById(entity)} 永远丢弃 null 字段，故旧实现从未真正置空
 * email/phone，已删用户持续占用 {@code uk_email}/{@code uk_phone}；新实现必须让 null 进入 SET 子句绑定参数。
 */
@DisplayName("用户批删单条化 + 置空修复（A-10）")
@ExtendWith(MockitoExtension.class)
class UserDeleteByIdsBatchTest {

  @Mock private UserMapper userMapper;

  @InjectMocks private UserServiceImpl service;

  @BeforeAll
  static void initTableInfo() {
    TableInfoHelper.initTableInfo(
        new MapperBuilderAssistant(new MybatisConfiguration(), ""), UserEntity.class);
  }

  @Test
  @DisplayName("批删：单条 update 携 SET status=3 + email=null + phone=null + IN idList")
  @SuppressWarnings("unchecked")
  void deleteById_singleBatchUpdateWithExplicitNulls() {
    when(userMapper.selectList(any())).thenReturn(List.of(user(11L), user(12L)));

    assertTrue(service.deleteById(List.of(11L, 12L)));

    ArgumentCaptor<LambdaUpdateWrapper<UserEntity>> captor =
        ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
    verify(userMapper, times(1)).update(isNull(), captor.capture());
    LambdaUpdateWrapper<UserEntity> wrapper = captor.getValue();

    String sqlSet = wrapper.getSqlSet();
    assertTrue(sqlSet.contains("status="), "SET 子句必须显式置 status");
    assertTrue(sqlSet.contains("email="), "SET 子句必须显式置 email");
    assertTrue(sqlSet.contains("phone="), "SET 子句必须显式置 phone");
    assertTrue(wrapper.getSqlSegment().contains("id IN"), "必须用 IN 一次命中全部 id");

    Map<String, Object> params = wrapper.getParamNameValuePairs();
    assertTrue(params.containsValue(3), "SET 值必须是已删除状态 3");
    assertTrue(params.containsValue(11L));
    assertTrue(params.containsValue(12L));
    // 置空新语义：email/phone 两个 null 必须绑定进 SET 参数（旧 updateById 路径下它们根本不会出现在 SQL 里）
    assertEquals(
        2,
        Collections.frequency(new ArrayList<>(params.values()), null),
        "email 与 phone 必须各有一个 null 绑定参数");

    // 旧的逐条更新必须彻底消失
    verify(userMapper, never()).updateById(any());
  }

  @Test
  @DisplayName("批删：预检闸 size 不符 → 91003「删除失败，请注意用户状态」且零写库")
  void deleteById_statusMismatch_throwsAndWritesNothing() {
    when(userMapper.selectList(any())).thenReturn(List.of(user(11L)));

    BaseException ex =
        assertThrows(BaseException.class, () -> service.deleteById(List.of(11L, 12L)));

    assertEquals(ErrorCode.DATA_DELETE_FAILED.getCode(), ex.getCode());
    assertEquals("删除失败，请注意用户状态", ex.getMessage());
    verify(userMapper, never()).update(any(), any());
    verify(userMapper, never()).updateById(any());
  }

  @Test
  @DisplayName("批删：重复 id 在预检闸即被拦下（查出 1 行 ≠ 入参 2 个）")
  void deleteById_duplicateIds_rejectedByPrecheck() {
    when(userMapper.selectList(any())).thenReturn(List.of(user(11L)));

    BaseException ex =
        assertThrows(BaseException.class, () -> service.deleteById(List.of(11L, 11L)));

    assertEquals(ErrorCode.DATA_DELETE_FAILED.getCode(), ex.getCode());
    verify(userMapper, never()).update(any(), any());
  }

  private static UserEntity user(Long id) {
    UserEntity entity = new UserEntity();
    entity.setId(id);
    entity.setStatus(0);
    return entity;
  }
}
