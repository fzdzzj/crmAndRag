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
import com.slz.crm.pojo.entity.InvoiceInfoEntity;
import com.slz.crm.server.mapper.ContractMapper;
import com.slz.crm.server.mapper.InvoiceInfoMapper;
import com.slz.crm.server.mapper.PaymentRecordMapper;
import com.slz.crm.server.mapper.UserMapper;
import com.slz.crm.server.service.impl.InvoiceInfoServiceImpl;
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
 * 开票信息批量软删除单测（fix-engineering-debt-nplus1-loop-clone 任务 2.4）。
 *
 * <p>锁定 A-9：deleteByIds 由「逐条 selectById + updateById」收敛为单条 {@code LambdaUpdateWrapper} （SET
 * status=1 + WHERE id IN (...)），逐条调用零次；返回计数以数据库实际命中行数为准。
 */
@DisplayName("开票信息批量软删除")
@ExtendWith(MockitoExtension.class)
class InvoiceInfoDeleteByIdsBatchTest {

  @Mock private InvoiceInfoMapper invoiceInfoMapper;

  @Mock private ContractMapper contractMapper;

  @Mock private PaymentRecordMapper paymentRecordMapper;

  @Mock private UserMapper userMapper;

  @InjectMocks private InvoiceInfoServiceImpl service;

  @BeforeAll
  static void initTableInfo() {
    TableInfoHelper.initTableInfo(
        new MapperBuilderAssistant(new MybatisConfiguration(), ""), InvoiceInfoEntity.class);
  }

  @BeforeEach
  void setUp() {
    ReflectionTestUtils.setField(service, "baseMapper", invoiceInfoMapper);
  }

  @SuppressWarnings("unchecked")
  private LambdaUpdateWrapper<InvoiceInfoEntity> captureBatchUpdate() {
    ArgumentCaptor<LambdaUpdateWrapper<InvoiceInfoEntity>> captor =
        ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
    verify(invoiceInfoMapper, times(1)).update(isNull(), captor.capture());
    return captor.getValue();
  }

  @Test
  @DisplayName("批量软删：单条 update 携 SET status=1 + IN idList，逐条 selectById/updateById 零调用")
  void deleteByIds_singleBatchUpdate() {
    when(invoiceInfoMapper.update(isNull(), any())).thenReturn(2);

    Integer count = service.deleteByIds(List.of(11L, 12L));

    assertEquals(2, count);
    LambdaUpdateWrapper<InvoiceInfoEntity> wrapper = captureBatchUpdate();
    String sqlSet = wrapper.getSqlSet();
    assertTrue(sqlSet.contains("status="), "SET 子句必须显式置 status");
    String where = wrapper.getSqlSegment();
    assertTrue(where.contains("id IN"), "必须用 IN 一次命中全部 id");
    Map<String, Object> params = wrapper.getParamNameValuePairs();
    assertTrue(params.containsValue(1), "SET 值必须是已作废状态 1");
    assertTrue(params.containsValue(11L));
    assertTrue(params.containsValue(12L));

    // 旧实现的 2N 条 SQL 必须彻底消失
    verify(invoiceInfoMapper, never()).selectById(anyLong());
    verify(invoiceInfoMapper, never()).updateById(any());
  }

  @Test
  @DisplayName("批量软删：重复 id 输入仍只发一条 update，计数以实际命中行数为准")
  void deleteByIds_duplicateIds_singleUpdateCountedByRows() {
    when(invoiceInfoMapper.update(isNull(), any())).thenReturn(1);

    Integer count = service.deleteByIds(List.of(11L, 11L));

    // 旧实现同一行会被 updateById 两次、计数 2；新实现按实际行数计 1（handoff 单列论证的行为差异）
    assertEquals(1, count);
    LambdaUpdateWrapper<InvoiceInfoEntity> wrapper = captureBatchUpdate();
    assertTrue(wrapper.getSqlSegment().contains("id IN"));
    verify(invoiceInfoMapper, never()).selectById(anyLong());
    verify(invoiceInfoMapper, never()).updateById(any());
  }

  @Test
  @DisplayName("批量软删：空入参抛 PARAM_EMPTY 且零数据库交互")
  void deleteByIds_emptyList_throws() {
    BaseException ex = assertThrows(BaseException.class, () -> service.deleteByIds(List.of()));
    assertEquals(ErrorCode.PARAM_EMPTY.getCode(), ex.getCode());
    assertThrows(BaseException.class, () -> service.deleteByIds(null));
    verifyNoInteractions(invoiceInfoMapper);
  }
}
