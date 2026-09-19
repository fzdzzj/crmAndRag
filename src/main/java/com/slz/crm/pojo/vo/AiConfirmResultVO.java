package com.slz.crm.pojo.vo;

import java.util.List;
import lombok.Data;

/** AI 确认执行结果：执行结果 JSON + 新创建实体的引用（前端卡片渲染跳转标签） */
@Data
public class AiConfirmResultVO {

  /** 执行结果 JSON（与原确认接口返回格式一致） */
  private String result;

  /** 新创建实体的引用（幂等返回与失败路径为空列表） */
  private List<ReferenceItem> references;

  @Data
  public static class ReferenceItem {
    /** contract/customerCompany/contact/opportunity/order/payment/invoice */
    private String type;

    private Long id;
    private String name;
  }
}
