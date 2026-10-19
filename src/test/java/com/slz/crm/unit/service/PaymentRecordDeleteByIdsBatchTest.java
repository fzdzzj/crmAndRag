package com.slz.crm.unit.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.slz.crm.common.enumeration.ErrorCode;
import com.slz.crm.common.exiception.BaseException;
import com.slz.crm.pojo.entity.PaymentRecordEntity;
import com.slz.crm.server.mapper.ContractMapper;
import com.slz.crm.server.mapper.ContractOrderItemMapper;
import com.slz.crm.server.mapper.PaymentRecordMapper;
import com.slz.crm.server.service.DataConvertService;
import com.slz.crm.server.service.impl.PaymentRecordServiceImpl;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 回款记录批量软删除单测（fix-engineering-debt-nplus1-loop-clone 任务 2.4）。
 *
 * <p>与开票侧同型：deleteByIds 收敛为单条 {@code LambdaUpdateWrapper}（SET paymentStatus=2 + WHERE id IN
 * (...))，逐条 selectById/updateById 零调用；返回计数以实际命中行数为准。
 */
@DisplayName("回款记录批量软删除")
@ExtendWith(MockitoExtension.class)
class PaymentRecordDeleteByIdsBatchTest {

  @Mock private PaymentRecordMapper paymentRecordMapper;

  @Mock private ContractMapper contractMapper;

  @Mock private ContractOrderItemMapper contractOrderItemMapper;

  @Mock private DataConvertService dataConvertService;

  @InjectMocks private PaymentRecordServiceImpl service;

  @BeforeAll
  static void initTableInfo() {
    TableInfoHelper.initTableInfo(
        new MapperBuilderAssistant(new MybatisConfiguration(), ""), PaymentRecordEntity.class);
  }

  @BeforeEach
  void setUp() {
    ReflectionTestUtils.setField(service, "baseMapper", paymentRecordMapper);
  }

  @SuppressWarnings("unchecked")
  private LambdaUpdateWrapper<PaymentRecordEntity> captureBatchUpdate() {
    ArgumentCaptor<LambdaUpdateWrapper<PaymentRecordEntity>> captor =
        ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
    verify(paymentRecordMapper, times(1)).update(isNull(), captor.capture());
    return captor.getValue();
  }

  @Test
  @DisplayName("批量软删：单条 update 携 SET paymentStatus=2 + IN idList，逐条 selectById/updateById 零调用")
  void deleteByIds_singleBatchUpdate() {
    when(paymentRecordMapper.update(isNull(), any())).thenReturn(2);

    Integer count = service.deleteByIds(List.of(21L, 22L));

    assertEquals(2, count);
    LambdaUpdateWrapper<PaymentRecordEntity> wrapper = captureBatchUpdate();
    String sqlSet = wrapper.getSqlSet();
    assertTrue(sqlSet.contains("payment_status="), "SET 子句必须显式置 paymentStatus");
    String where = wrapper.getSqlSegment();
    assertTrue(where.contains("id IN"), "必须用 IN 一次命中全部 id");
    Map<String, Object> params = wrapper.getParamNameValuePairs();
    assertTrue(params.containsValue(2), "SET 值必须是已作废状态 2");
    assertTrue(params.containsValue(21L));
    assertTrue(params.containsValue(22L));

    verify(paymentRecordMapper, never()).selectById(anyLong());
    verify(paymentRecordMapper, never()).updateById(any());
  }

  @Test
  @DisplayName("批量软删：重复 id 输入仍只发一条 update，计数以实际命中行数为准")
  void deleteByIds_duplicateIds_singleUpdateCountedByRows() {
    when(paymentRecordMapper.update(isNull(), any())).thenReturn(1);

    Integer count = service.deleteByIds(List.of(21L, 21L));

    assertEquals(1, count);
    LambdaUpdateWrapper<PaymentRecordEntity> wrapper = captureBatchUpdate();
    assertTrue(wrapper.getSqlSegment().contains("id IN"));
    verify(paymentRecordMapper, never()).selectById(anyLong());
    verify(paymentRecordMapper, never()).updateById(any());
  }

  @Test
  @DisplayName("批量软删：空入参抛 PARAM_EMPTY 且零数据库交互")
  void deleteByIds_emptyList_throws() {
    BaseException ex = assertThrows(BaseException.class, () -> service.deleteByIds(List.of()));
    assertEquals(ErrorCode.PARAM_EMPTY.getCode(), ex.getCode());
    assertThrows(BaseException.class, () -> service.deleteByIds(null));
    verifyNoInteractions(paymentRecordMapper);
  }
}
