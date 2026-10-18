package com.slz.crm.unit.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.slz.crm.common.enumeration.ErrorCode;
import com.slz.crm.common.exiception.BaseException;
import com.slz.crm.pojo.entity.ContractEntity;
import com.slz.crm.pojo.entity.ContractOrderItemEntity;
import com.slz.crm.pojo.vo.OrderVO;
import com.slz.crm.server.mapper.ContractMapper;
import com.slz.crm.server.service.BusinessRecordAccessService;
import com.slz.crm.server.service.impl.ContractOrderItemServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 订单详情对象级读授权（harden-consistency-datascope-and-gate 模块 B）：订单读权限随所属合同， 断言挂在 {@code
 * BusinessRecordAccessService#assertCanReadContract} 上。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("合同订单详情对象级授权")
class ContractOrderItemServiceImplTest {

  @Mock private BusinessRecordAccessService businessRecordAccessService;

  @Mock private ContractMapper contractMapper;

  @Test
  @DisplayName("订单存在但所属合同缺失时 fail-closed 拒绝")
  void getDetailByIdDeniedWhenContractMissing() {
    ContractOrderItemServiceImpl service = spy(new ContractOrderItemServiceImpl());
    ReflectionTestUtils.setField(
        service, "businessRecordAccessService", businessRecordAccessService);
    ReflectionTestUtils.setField(service, "contractMapper", contractMapper);
    ContractOrderItemEntity item = new ContractOrderItemEntity();
    item.setId(77L);
    item.setContractId(5L);
    doReturn(item).when(service).getById(77L);
    when(contractMapper.selectById(5L)).thenReturn(null);

    BaseException ex = assertThrows(BaseException.class, () -> service.getDetailById(77L));

    assertEquals(ErrorCode.PERMISSION_DENIED.getCode(), ex.getCode());
    verifyNoInteractions(businessRecordAccessService);
  }

  @Test
  @DisplayName("订单不存在时报实体缺失，不进入授权断言")
  void getDetailByIdMissingOrderThrowsNotFound() {
    ContractOrderItemServiceImpl service = spy(new ContractOrderItemServiceImpl());
    ReflectionTestUtils.setField(
        service, "businessRecordAccessService", businessRecordAccessService);
    doReturn(null).when(service).getById(77L);

    BaseException ex = assertThrows(BaseException.class, () -> service.getDetailById(77L));

    assertEquals(ErrorCode.ORDER_NOT_EXISTS.getCode(), ex.getCode());
    verifyNoInteractions(contractMapper, businessRecordAccessService);
  }

  @Test
  @DisplayName("跨属主读取订单详情被 12002 拦截")
  void getDetailByIdDeniedForUnrelatedContract() {
    ContractOrderItemServiceImpl service = spy(new ContractOrderItemServiceImpl());
    ReflectionTestUtils.setField(
        service, "businessRecordAccessService", businessRecordAccessService);
    ReflectionTestUtils.setField(service, "contractMapper", contractMapper);
    ContractOrderItemEntity item = new ContractOrderItemEntity();
    item.setId(77L);
    item.setContractId(5L);
    ContractEntity contract = new ContractEntity();
    contract.setId(5L);
    doReturn(item).when(service).getById(77L);
    when(contractMapper.selectById(5L)).thenReturn(contract);
    doThrow(new BaseException(ErrorCode.PERMISSION_DENIED))
        .when(businessRecordAccessService)
        .assertCanReadContract(contract);

    BaseException ex = assertThrows(BaseException.class, () -> service.getDetailById(77L));

    assertEquals(ErrorCode.PERMISSION_DENIED.getCode(), ex.getCode());
    verify(businessRecordAccessService).assertCanReadContract(contract);
  }

  @Test
  @DisplayName("合法属主读取订单详情正常返回")
  void getDetailByIdReturnsForOwnerInScope() {
    ContractOrderItemServiceImpl service = spy(new ContractOrderItemServiceImpl());
    ReflectionTestUtils.setField(
        service, "businessRecordAccessService", businessRecordAccessService);
    ReflectionTestUtils.setField(service, "contractMapper", contractMapper);
    ContractOrderItemEntity item = new ContractOrderItemEntity();
    item.setId(77L);
    item.setContractId(5L);
    ContractEntity contract = new ContractEntity();
    contract.setId(5L);
    doReturn(item).when(service).getById(77L);
    when(contractMapper.selectById(5L)).thenReturn(contract);
    when(businessRecordAccessService.assertCanReadContract(contract)).thenReturn(true);

    OrderVO result = service.getDetailById(77L);

    assertNotNull(result);
    assertEquals(77L, result.getId());
  }
}
