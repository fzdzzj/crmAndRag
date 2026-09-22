package com.slz.crm.server.ai;

import com.slz.crm.pojo.vo.ContractVO;
import com.slz.crm.pojo.vo.CustomerCompanyVO;
import com.slz.crm.pojo.vo.CustomerContactVO;
import com.slz.crm.pojo.vo.InvoiceInfoVO;
import com.slz.crm.pojo.vo.OrderVO;
import com.slz.crm.pojo.vo.PaymentRecordVO;
import com.slz.crm.pojo.vo.SalesOpportunityVO;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AI 回答实体引用采集器：每轮对话收集只读工具结果中的业务实体（type + id + name）， 去重后经 SSE references 事件下发，前端渲染为可跳转详情的引用条目。
 * 工具结果已按数据权限过滤，引用不引入新的数据暴露面。
 */
public class AiReferenceCollector {

  /** 实体引用（type 决定前端跳转目标） */
  public record Reference(String type, Long id, String name) {}

  /** type:id → 引用，去重且保持插入顺序 */
  private final Map<String, Reference> references =
      Collections.synchronizedMap(new LinkedHashMap<>());

  /** 从工具结果 VO 提取引用（非实体类型返回 null） */
  public static Reference fromVo(Object vo) {
    Reference reference = null;
    if (vo instanceof ContractVO v) {
      reference = new Reference("contract", v.getId(), v.getContractName());
    } else if (vo instanceof CustomerCompanyVO v) {
      reference = new Reference("customerCompany", v.getId(), v.getCompanyName());
    } else if (vo instanceof CustomerContactVO v) {
      reference = new Reference("contact", v.getId(), v.getName());
    } else if (vo instanceof SalesOpportunityVO v) {
      reference = new Reference("opportunity", v.getId(), v.getOpportunityName());
    } else if (vo instanceof OrderVO v) {
      reference = new Reference("order", v.getId(), v.getProductName());
    } else if (vo instanceof PaymentRecordVO v) {
      reference = new Reference("payment", v.getId(), v.getContractName());
    } else if (vo instanceof InvoiceInfoVO v) {
      reference = new Reference("invoice", v.getId(), v.getInvoiceNo());
    }
    return reference;
  }

  /** 采集单个 VO（非实体类型忽略） */
  public void collect(Object vo) {
    Reference reference = fromVo(vo);
    if (reference != null
        && reference.id() != null
        && reference.name() != null
        && !reference.name().isBlank()) {
      references.putIfAbsent(reference.type() + ":" + reference.id(), reference);
    }
  }

  public List<Reference> getReferences() {
    synchronized (references) {
      return List.copyOf(references.values());
    }
  }

  public boolean isEmpty() {
    return references.isEmpty();
  }
}
