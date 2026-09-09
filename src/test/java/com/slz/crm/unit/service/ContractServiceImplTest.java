package com.slz.crm.unit.service;

import com.slz.crm.common.exiception.BaseException;
import com.slz.crm.pojo.dto.ContractDTO;
import com.slz.crm.pojo.dto.OrderDTO;
import com.slz.crm.pojo.vo.ContractVO;
import com.slz.crm.pojo.vo.OrderVO;
import com.slz.crm.server.service.ContractOrderItemService;
import com.slz.crm.server.service.impl.ContractServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("合同服务创建编排")
class ContractServiceImplTest {

    @Mock
    private ContractOrderItemService contractOrderItemService;

    @Test
    @DisplayName("订单统一挂靠新合同并校验创建数量")
    void createWithOrdersAttachesOrdersAndValidatesCount() {
        ContractServiceImpl service = spy(new ContractServiceImpl());
        ReflectionTestUtils.setField(service, "contractOrderItemService", contractOrderItemService);
        ContractVO created = new ContractVO();
        created.setId(9L);
        doReturn(created).when(service).create(any(ContractDTO.class));
        when(contractOrderItemService.createBatch(anyList())).thenReturn(List.of(new OrderVO(), new OrderVO()));

        ContractVO result = service.createWithOrders(new ContractDTO(), List.of(new OrderDTO(), new OrderDTO()));

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

        assertThrows(IllegalStateException.class,
                () -> service.createWithOrders(new ContractDTO(), List.of(new OrderDTO(), new OrderDTO())));
    }

    @Test
    @DisplayName("合同创建失败时不创建订单")
    void createWithOrdersDoesNotCreateOrdersWhenContractFails() {
        ContractServiceImpl service = spy(new ContractServiceImpl());
        ReflectionTestUtils.setField(service, "contractOrderItemService", contractOrderItemService);
        doReturn(null).when(service).create(any(ContractDTO.class));

        assertThrows(IllegalStateException.class,
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

    @SuppressWarnings("unchecked")
    private List<OrderDTO> listArgument() {
        org.mockito.ArgumentCaptor<List<OrderDTO>> captor = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(contractOrderItemService).createBatch(captor.capture());
        return captor.getValue();
    }
}
