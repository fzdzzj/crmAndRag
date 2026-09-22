package com.slz.crm.server.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.slz.crm.pojo.dto.ai.AiDraftResult;
import com.slz.crm.pojo.entity.AiPendingActionEntity;
import com.slz.crm.pojo.vo.AiConfirmResultVO;
import com.slz.crm.pojo.vo.AiPendingActionVO;
import com.slz.crm.server.ai.AiReferenceCollector;
import com.slz.crm.server.ai.validation.AiValidationResult;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;

/**
 * AI 待确认操作的 payload/JSON 装配支持类（tighten-pmd-residual-325 任务 6.3 拆自
 * PendingActionServiceImpl，行为等价）。纯静态、无状态，全部依赖经参数传入。
 */
@Slf4j
final class PendingActionPayloadSupport {

  private static final DateTimeFormatter PENDING_ID_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  private PendingActionPayloadSupport() {}

  /** 生成 pendingId（PA + 日期 + 8位随机字符） */
  static String generatePendingId() {
    String date = LocalDateTime.now().format(PENDING_ID_FMT);

    String random =
        Long.toHexString(ThreadLocalRandom.current().nextLong()).substring(0, 8).toUpperCase();

    return "PA" + date + random;
  }

  /** 合并 payload（增量覆盖/补入） */
  static String mergePayload(String existingPayload, String incrementJson) {
    String result;
    try {
      Map<String, Object> existing =
          OBJECT_MAPPER.readValue(existingPayload, new TypeReference<Map<String, Object>>() {});

      Map<String, Object> increment =
          OBJECT_MAPPER.readValue(incrementJson, new TypeReference<Map<String, Object>>() {});

      increment.remove("pendingId"); // pendingId 不属于业务参数

      existing.putAll(increment);

      result = OBJECT_MAPPER.writeValueAsString(existing);

    } catch (JsonProcessingException e) {

      log.error("合并 payload 失败", e);

      result = incrementJson;
    }
    return result;
  }

  /** 构建确认卡片预览摘要（以 payload 为准，不依赖模型总结） */
  static String buildPreview(String payloadJson) {
    Map<String, Object> preview = new HashMap<>();

    preview.put("summary", "请确认以下操作参数");

    preview.put("payload", payloadJson);

    return toJson(preview);
  }

  /** 组装草稿结果（校验结果 + 实体快照） */
  static AiDraftResult toDraftResult(AiPendingActionEntity entity, AiValidationResult vr) {
    return AiDraftResult.builder()
        .pendingId(entity.getPendingId())
        .status(entity.getStatus())
        .missingFields(vr.getMissingFields())
        .questions(vr.getQuestions())
        .askRound(entity.getAskRound())
        .preview(entity.getPreview())
        .build();
  }

  /** 组装确认结果（执行结果 + 新创建实体引用） */
  static AiConfirmResultVO toConfirmResult(
      String result, List<AiReferenceCollector.Reference> references) {
    AiConfirmResultVO vo = new AiConfirmResultVO();

    vo.setResult(result);

    List<AiConfirmResultVO.ReferenceItem> items = new ArrayList<>();

    if (references != null) {

      for (AiReferenceCollector.Reference reference : references) {

        AiConfirmResultVO.ReferenceItem item = new AiConfirmResultVO.ReferenceItem();

        item.setType(reference.type());

        item.setId(reference.id());

        item.setName(reference.name());

        items.add(item);
      }
    }
    vo.setReferences(items);

    return vo;
  }

  static AiPendingActionVO toVO(AiPendingActionEntity entity) {
    AiPendingActionVO vo = new AiPendingActionVO();

    BeanUtils.copyProperties(entity, vo);

    return vo;
  }

  static String toJson(Object obj) {
    String result;
    try {
      result = OBJECT_MAPPER.writeValueAsString(obj);

    } catch (JsonProcessingException e) {

      result = "[]";
    }
    return result;
  }
}
