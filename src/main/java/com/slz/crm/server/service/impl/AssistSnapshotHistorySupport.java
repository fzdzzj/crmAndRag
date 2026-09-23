package com.slz.crm.server.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.slz.crm.common.enumeration.ModelName;
import com.slz.crm.common.untils.AttachmentDownloadTokenUtil;
import com.slz.crm.common.untils.BaseUnit;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;

/**
 * 历史快照附件链接装配（tighten-pmd-residual-325 任务 6.5，拆自 AssistRequestServiceImpl）。
 *
 * <p>归属主类入口 {@code getDetail} 的终态分支。只在内存中改写快照 JSON，不落库、不带事务注解、不新开事务。
 *
 * <p>这里不查询来源任务、活动、审批来重建快照，保证协助结束后看到的业务内容不漂移。 下载接口仍会再次验证附件记录与物理文件是否存在，因此文件之后被删除时链接自然失效。
 */
@Slf4j
class AssistSnapshotHistorySupport {

  private final ObjectMapper objectMapper;
  private final AttachmentDownloadTokenUtil downloadTokenUtil;
  private final HttpServletRequest httpRequest;

  AssistSnapshotHistorySupport(
      ObjectMapper objectMapper,
      AttachmentDownloadTokenUtil downloadTokenUtil,
      HttpServletRequest httpRequest) {
    this.objectMapper = objectMapper;
    this.downloadTokenUtil = downloadTokenUtil;
    this.httpRequest = httpRequest;
  }

  /**
   * 在内存中给终态快照补充短期下载链接，不修改数据库中的原始快照。
   *
   * <p>单出口化（任务 6.5）：解析失败/结构不符一律回落原快照，用结果变量表达比四处早返回更清楚。
   */
  @SuppressWarnings("PMD.AvoidCatchingGenericException") // 快照解析递归签发令牌多源，失败必须回落原快照而不是打断详情读取
  String hydrateHistoricalAttachmentLinks(String snapshot, Long assistId) {
    String result = snapshot;
    if (snapshot != null && !snapshot.isBlank() && assistId != null) {
      try {
        Object root = objectMapper.readValue(snapshot, Object.class);
        if (root instanceof Map<?, ?>) {
          addHistoricalAttachmentLinks(root, assistId, null);
          result = objectMapper.writeValueAsString(root);
        }
      } catch (Exception exception) {
        log.warn("协助历史附件链接生成失败，assistId={}", assistId, exception);
        result = snapshot;
      }
    }
    return result;
  }

  /**
   * 递归遍历快照中的对象和数组，找到 {@code attachmentId} 后签发历史下载令牌。
   *
   * <p>{@code inheritedModelName} 用于处理旧版本快照：旧快照可能没有在每个附件上保存 {@code
   * modelName}，但其父节点已能确定来源模型；交付物则固定属于 {@code ASSIST_REQUEST}。
   */
  @SuppressWarnings("unchecked")
  private void addHistoricalAttachmentLinks(Object node, Long assistId, String inheritedModelName) {
    if (node instanceof Map<?, ?> rawMap) {
      Map<String, Object> map = (Map<String, Object>) rawMap;
      String modelName = attachmentModelName(map, inheritedModelName);
      Long attachmentId = longValue(map.get("attachmentId"));
      if (attachmentId != null && modelName != null) {
        Long currentUserId = BaseUnit.getCurrentId();
        String token =
            downloadTokenUtil.generateDownloadToken(
                attachmentId, currentUserId, "approval_attachment", modelName, assistId);
        map.put("downloadUrl", attachmentBaseUrl() + "/public/attachment/download?token=" + token);
      }
      for (Map.Entry<String, Object> entry : map.entrySet()) {
        // 交付物不属于历史业务快照；终态交付物统一走 /assist/{id}/attachments 实时查询。
        if (!"deliveryAttachments".equals(entry.getKey())) {
          addHistoricalAttachmentLinks(entry.getValue(), assistId, modelName);
        }
      }
    } else if (node instanceof Iterable<?> values) {
      for (Object value : values) {
        addHistoricalAttachmentLinks(value, assistId, inheritedModelName);
      }
    }
  }

  /** 优先读取新快照中的 modelName；若是旧快照则从记录 type 或 activityId 推断模型， 保证存量历史记录也能使用新的下载链路。 */
  private String attachmentModelName(Map<String, Object> map, String inheritedModelName) {
    // 取值优先级自低到高依次覆盖：继承模型 → 记录 type 推断 → 显式 modelName。
    String modelName =
        map.containsKey("activityId") ? ModelName.BUSINESS_ACTIVITY : inheritedModelName;
    String recordType = stringValue(map.get("type"));
    if (recordType != null) {
      modelName =
          switch (recordType) {
            case "salesStageApproval" -> ModelName.APPROVAL_ATTACHMENT;
            case "businessActivity" -> ModelName.BUSINESS_ACTIVITY;
            case "contactTask" -> ModelName.CONTACT_TASK;
            default -> inheritedModelName;
          };
    }
    String explicitModelName = stringValue(map.get("modelName"));
    if (explicitModelName != null) {
      modelName = attachmentModelForSource(explicitModelName);
    }
    return modelName;
  }

  /** 审批业务的来源模型名与附件表使用的模型名不同，需要在签发令牌前转换。 */
  private String attachmentModelForSource(String modelName) {
    return ModelName.SALES_STAGE_APPROVAL.equals(modelName)
        ? ModelName.APPROVAL_ATTACHMENT
        : modelName;
  }

  // Jackson 反序列化旧快照后，数字可能是 Number 或 String；统一转换以兼容两种格式。
  private static Long longValue(Object value) {
    Long result = null;
    if (value instanceof Number number) {
      result = number.longValue();
    } else if (value instanceof String text) {
      try {
        result = Long.valueOf(text);
      } catch (NumberFormatException ignored) {
        result = null;
      }
    }
    return result;
  }

  private static String stringValue(Object value) {
    return value instanceof String text && !text.isBlank() ? text : null;
  }

  /** 下载地址只需要当前应用的 context path，不拼 host，前端会按当前服务域名发起请求。 */
  private String attachmentBaseUrl() {
    String contextPath = httpRequest.getContextPath();
    return contextPath == null ? "" : contextPath;
  }
}
