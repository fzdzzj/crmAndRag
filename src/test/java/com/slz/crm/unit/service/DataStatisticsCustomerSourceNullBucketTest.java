package com.slz.crm.unit.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.slz.crm.common.enumeration.ChartDataType;
import com.slz.crm.pojo.dto.ContractChartDTO;
import com.slz.crm.pojo.dto.DataStatisticsDTO;
import com.slz.crm.pojo.vo.ChartDataVO;
import com.slz.crm.server.mapper.ContractMapper;
import com.slz.crm.server.service.impl.DataStatisticsServiceImpl;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * TASK-15 红①：客户来源分布聚合的 NULL / 全空白归桶单测（无 Docker，mock mapper）。
 *
 * <p>V28 后 {@code customer_company.source} 可空、存量行全 NULL，mapper 会返回 {@code chartTitle} 为 null 的行。修复前
 * 2 参 {@code Collectors.toMap} 在 JVM 内并不 NPE——HashMap 允许 null key，于是结果 Map 静默带上 null 与纯空白两个非法
 * key；真库对外时 Jackson 拒绝 Map null key → HTTP 500（TASK-14 实测）。修复后 null 与 trim 后为空的来源统一并入 {@code "未标注"}
 * 桶，且带 merge 函数防键冲突。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("客户来源分布：未标注来源归桶")
class DataStatisticsCustomerSourceNullBucketTest {

  @Mock private ContractMapper contractMapper;

  private DataStatisticsServiceImpl newService() {
    DataStatisticsServiceImpl service = new DataStatisticsServiceImpl();
    ReflectionTestUtils.setField(service, "contractMapper", contractMapper);
    return service;
  }

  private static ContractChartDTO row(String chartTitle, long num) {
    ContractChartDTO dto = new ContractChartDTO();
    dto.setChartTitle(chartTitle);
    dto.setNum(num);
    return dto;
  }

  private static DataStatisticsDTO customerSourceQuery() {
    DataStatisticsDTO dto = new DataStatisticsDTO();
    dto.setDataType(ChartDataType.CUSTOMER_SOURCE);
    return dto;
  }

  @Test
  @DisplayName("null 与全空白来源并入未标注桶，正常来源各自成桶")
  void bucketsNullAndBlankSourceIntoUnlabeled() throws Exception {
    List<ContractChartDTO> chartData = new ArrayList<>();
    chartData.add(row(null, 3L));
    chartData.add(row("官网", 2L));
    chartData.add(row("  ", 1L));
    when(contractMapper.selectCompanySourceByPeriod(any(), any(), any())).thenReturn(chartData);

    ChartDataVO vo = newService().getChartData(customerSourceQuery());
    Map<String, Number> data = vo.getData();

    // null(3) 与 "  "(1) 合并进未标注 → 4；官网 → 2；总共 2 个桶。
    assertEquals(2, data.size(), "应只剩未标注 + 官网两个桶：" + data);
    assertEquals(4L, data.get("未标注").longValue());
    assertEquals(2L, data.get("官网").longValue());
    // 绝不能再出现 null key 或纯空白 key。
    assertFalse(data.containsKey(null), "结果 Map 不应含 null key");
    assertFalse(data.containsKey("  "), "结果 Map 不应含纯空白 key");
    data.keySet().forEach(key -> assertTrue(key != null && !key.trim().isEmpty()));
  }
}
