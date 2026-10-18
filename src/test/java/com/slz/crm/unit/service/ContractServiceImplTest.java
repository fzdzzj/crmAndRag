package com.slz.crm.unit.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.slz.crm.common.enumeration.ErrorCode;
import com.slz.crm.common.exiception.BaseException;
import com.slz.crm.pojo.dto.ContractDTO;
import com.slz.crm.pojo.dto.OrderDTO;
import com.slz.crm.pojo.entity.ContractEntity;
import com.slz.crm.pojo.vo.ContractANDOrderVO;
import com.slz.crm.pojo.vo.ContractVO;
import com.slz.crm.pojo.vo.OrderVO;
import com.slz.crm.server.service.BusinessRecordAccessService;
import com.slz.crm.server.service.ContractOrderItemService;
import com.slz.crm.server.service.DataConvertService;
import com.slz.crm.server.service.impl.ContractServiceImpl;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
@DisplayName("合同服务创建编排")
class ContractServiceImplTest {

  @Mock private ContractOrderItemService contractOrderItemService;
  @Mock private BusinessRecordAccessService businessRecordAccessService;
  @Mock private DataConvertService dataConvertService;

  @Test
  @DisplayName("订单统一挂靠新合同并校验创建数量")
  void createWithOrdersAttachesOrdersAndValidatesCount() {
    ContractServiceImpl service = spy(new ContractServiceImpl());
    ReflectionTestUtils.setField(service, "contractOrderItemService", contractOrderItemService);
    ContractVO created = new ContractVO();
    created.setId(9L);
    doReturn(created).when(service).create(any(ContractDTO.class));
    when(contractOrderItemService.createBatch(anyList()))
        .thenReturn(List.of(new OrderVO(), new OrderVO()));

    ContractVO result =
        service.createWithOrders(new ContractDTO(), List.of(new OrderDTO(), new OrderDTO()));

    assertEquals(9L, result.getId());
    assertEquals(9L, listArgument().get(0).getContractId());
    assertEquals(9L, listArgument().get(1).getContractId());
  }

  @Test
  @DisplayName("订单数量不足时抛出并依赖事务回滚")
  void createWithOrdersFailsWhenOrderCountMismatches() {
    ContractServiceImpl service = spy(new ContractServiceImpl());
    ReflectionTestUtils.setField(service, "contractOrderItemService", contractOrderItemService);
    ContractVO created = new ContractVO();
    created.setId(9L);
    doReturn(created).when(service).create(any(ContractDTO.class));
    when(contractOrderItemService.createBatch(anyList())).thenReturn(List.of(new OrderVO()));

    assertThrows(
        IllegalStateException.class,
        () -> service.createWithOrders(new ContractDTO(), List.of(new OrderDTO(), new OrderDTO())));
  }

  @Test
  @DisplayName("合同创建失败时不创建订单")
  void createWithOrdersDoesNotCreateOrdersWhenContractFails() {
    ContractServiceImpl service = spy(new ContractServiceImpl());
    ReflectionTestUtils.setField(service, "contractOrderItemService", contractOrderItemService);
    doReturn(null).when(service).create(any(ContractDTO.class));

    assertThrows(
        IllegalStateException.class,
        () -> service.createWithOrders(new ContractDTO(), List.of(new OrderDTO())));

    verify(contractOrderItemService, never()).createBatch(anyList());
  }

  @Test
  @DisplayName("合同数据为空时抛出业务异常")
  void createWithOrdersRejectsNullContract() {
    ContractServiceImpl service = spy(new ContractServiceImpl());
    ReflectionTestUtils.setField(service, "contractOrderItemService", contractOrderItemService);

    assertThrows(BaseException.class, () -> service.createWithOrders(null, List.of()));
    verify(contractOrderItemService, never()).createBatch(anyList());
  }

  @Test
  @DisplayName("合同不存在时报实体缺失，不进入授权断言")
  void getDetailByIdMissingContractThrowsNotFound() {
    ContractServiceImpl service = spy(new ContractServiceImpl());
    ReflectionTestUtils.setField(
        service, "businessRecordAccessService", businessRecordAccessService);
    doReturn(null).when(service).getById(9L);

    BaseException ex = assertThrows(BaseException.class, () -> service.getDetailById(9L));

    assertEquals(ErrorCode.CONTRACT_NOT_EXISTS.getCode(), ex.getCode());
    verifyNoInteractions(businessRecordAccessService);
  }

  @Test
  @DisplayName("跨属主读取合同详情被 12002 拦截")
  void getDetailByIdDeniedForUnrelatedOwner() {
    ContractServiceImpl service = spy(new ContractServiceImpl());
    ReflectionTestUtils.setField(
        service, "businessRecordAccessService", businessRecordAccessService);
    ContractEntity entity = new ContractEntity();
    entity.setId(9L);
    doReturn(entity).when(service).getById(9L);
    doThrow(new BaseException(ErrorCode.PERMISSION_DENIED))
        .when(businessRecordAccessService)
        .assertCanReadContract(entity);

    BaseException ex = assertThrows(BaseException.class, () -> service.getDetailById(9L));

    assertEquals(ErrorCode.PERMISSION_DENIED.getCode(), ex.getCode());
    verify(businessRecordAccessService).assertCanReadContract(entity);
  }

  @Test
  @DisplayName("合法属主读取合同详情正常返回")
  void getDetailByIdReturnsForOwnerInScope() {
    ContractServiceImpl service = spy(new ContractServiceImpl());
    ReflectionTestUtils.setField(
        service, "businessRecordAccessService", businessRecordAccessService);
    ReflectionTestUtils.setField(service, "dataConvertService", dataConvertService);
    ReflectionTestUtils.setField(service, "contractOrderItemService", contractOrderItemService);
    ContractEntity entity = new ContractEntity();
    entity.setId(9L);
    entity.setCompanyId(1L);
    entity.setOpportunityId(2L);
    entity.setOwnerId(3L);
    entity.setCreatorId(4L);
    entity.setContractStatus(1);
    doReturn(entity).when(service).getById(9L);
    when(businessRecordAccessService.assertCanReadContract(entity)).thenReturn(true);
    when(dataConvertService.getCompanyName(1L)).thenReturn("公司A");
    when(dataConvertService.getOpportunityName(2L)).thenReturn("商机B");
    when(dataConvertService.getUserName(3L)).thenReturn("张三");
    when(dataConvertService.getUserName(4L)).thenReturn("李四");
    when(contractOrderItemService.getOrderListByContractId(9L)).thenReturn(List.of());

    ContractANDOrderVO result = service.getDetailById(9L);

    assertNotNull(result);
    assertNotNull(result.getContract());
    assertEquals(9L, result.getContract().getId());
  }

  @SuppressWarnings("unchecked")
  private List<OrderDTO> listArgument() {
    org.mockito.ArgumentCaptor<List<OrderDTO>> captor =
        org.mockito.ArgumentCaptor.forClass(List.class);
    verify(contractOrderItemService).createBatch(captor.capture());
    return captor.getValue();
  }
}
