package com.slz.crm.knowledge.document;

import com.slz.crm.platform.contract.ModelCallResult;
import com.slz.crm.platform.contract.TokenUsageRecord;
import com.slz.crm.platform.contract.TokenUsageRecorder;
import com.slz.crm.platform.contract.TokenUsageType;
import com.slz.crm.platform.contract.UserContext;
import com.slz.crm.platform.contract.UserContextHolder;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;

/**
 * 视觉摄取公共形态（add-kb-image-slice-retrieval 任务 1.2，对齐 SemanticHookSupport 的「唯一实现 + 薄门面」先例）。
 *
 * <p>整页视觉转写（{@link PdfVisionTranscriber}）与内嵌图片描述（{@link PdfImageSliceIngestService}）共用同一质量闸门与
 * VISION 计量口径：闸门阈值/「（截图不清）」比例/汉字符号统计只在这里实现一份，两个摄取入口各自只保留开关、预算帽与外呼编排。
 */
final class VisionIngestSupport {

  private static final Logger LOG = LoggerFactory.getLogger(VisionIngestSupport.class);

  /** 模型无法确认时要求标注的记号（与转写提示词纪律一致）。 */
  static final String UNCLEAR_MARK = "（截图不清）";

  /** 去空白后最短合格长度：整页转写与单图描述同口径（默认 40 字）。 */
  static final int MIN_COMPACT_CHARS = 40;

  /** 「（截图不清）」次数 / 汉字数 上限；超过则闸门失败。 */
  static final double UNCLEAR_HAN_RATIO_LIMIT = 0.4;

  private static final Pattern HAN_PATTERN = Pattern.compile("\\p{IsHan}");

  private VisionIngestSupport() {}

  /** 质量闸门（默认阈值 40 字）：blank / 过短 / 全是「（截图不清）」→ 失败。 */
  static boolean passesQualityGate(String raw) {
    return passesQualityGate(raw, MIN_COMPACT_CHARS);
  }

  /**
   * 质量闸门（可指定最短长度，供单图描述等更短产物复用同一判定形态）。
   *
   * @param raw 模型输出
   * @param minCompactChars 去空白后最短合格长度（&lt;1 按 1 计）
   * @return 是否合格
   */
  static boolean passesQualityGate(String raw, int minCompactChars) {
    boolean ok = raw != null && !raw.isBlank();
    if (ok) {
      String compact = raw.replaceAll("\\s+", "");
      ok = compact.length() >= Math.max(1, minCompactChars);
    }
    if (ok) {
      int unclear = countOccurrences(raw, UNCLEAR_MARK);
      int han = countHan(raw);
      if (unclear > 0 && han == 0) {
        ok = false;
      }
      if (ok && han > 0 && ((double) unclear / (double) han) > UNCLEAR_HAN_RATIO_LIMIT) {
        ok = false;
      }
    }
    return ok;
  }

  /**
   * VISION token 计量（失败/空结果也记录，success=false；计量侧任何异常只记 debug，不挡摄取）。
   *
   * @param result 模型调用结果（可空）
   * @param success 是否过闸门并被采纳
   * @param recorderProvider 计量记录器（可空，兼容构造下不计量）
   */
  @SuppressWarnings("PMD.AvoidCatchingGenericException") // 计量上报边界：失败忽略不挡摄取
  static void recordUsage(
      ModelCallResult<String> result,
      boolean success,
      ObjectProvider<TokenUsageRecorder> recorderProvider) {
    TokenUsageRecorder recorder =
        recorderProvider == null ? null : recorderProvider.getIfAvailable();
    if (recorder != null) {
      try {
        String model = result == null || result.model() == null ? "unknown" : result.model();
        recorder.record(
            new TokenUsageRecord(
                model,
                currentUserRef(),
                null,
                null,
                TokenUsageType.VISION,
                result == null ? null : result.promptTokens(),
                result == null ? null : result.completionTokens(),
                result == null ? null : result.totalTokens(),
                success));
      } catch (Exception ignore) {
        LOG.debug("VISION token 计量上报失败（忽略）", ignore);
      }
    }
  }

  /** 当前用户引用（无上下文时落 system）。 */
  static String currentUserRef() {
    UserContext user = UserContextHolder.current();
    return user == null ? "user:system" : user.userIdRef();
  }

  /**
   * EMBEDDING token 计量（multimodal-shared-space 任务 3.1：真视觉 embedding 与文本 embedding 同一计量类型）。
   *
   * <p>与 {@link #recordUsage} 同形态：失败/空结果也记录（success=false），计量侧异常只记 debug，不挡摄取。
   *
   * @param result 视觉 embedding 结果（可空）
   * @param success 是否取得可用向量
   * @param recorderProvider 计量记录器（可空，兼容构造下不计量）
   */
  @SuppressWarnings("PMD.AvoidCatchingGenericException") // 计量上报边界：失败忽略不挡摄取
  static void recordEmbeddingUsage(
      ModelCallResult<float[]> result,
      boolean success,
      ObjectProvider<TokenUsageRecorder> recorderProvider) {
    TokenUsageRecorder recorder =
        recorderProvider == null ? null : recorderProvider.getIfAvailable();
    if (recorder != null) {
      try {
        String model = result == null || result.model() == null ? "unknown" : result.model();
        recorder.record(
            new TokenUsageRecord(
                model,
                currentUserRef(),
                null,
                null,
                TokenUsageType.EMBEDDING,
                result == null ? null : result.promptTokens(),
                null,
                result == null ? null : result.totalTokens(),
                success));
      } catch (Exception ignore) {
        LOG.debug("EMBEDDING token 计量上报失败（忽略）", ignore);
      }
    }
  }

  private static int countOccurrences(String text, String needle) {
    int count = 0;
    if (text != null && needle != null && !needle.isEmpty()) {
      int from = 0;
      while (true) {
        int at = text.indexOf(needle, from);
        if (at < 0) {
          break;
        }
        count++;
        from = at + needle.length();
      }
    }
    return count;
  }

  private static int countHan(String text) {
    int count = 0;
    if (text != null && !text.isEmpty()) {
      Matcher matcher = HAN_PATTERN.matcher(text);
      while (matcher.find()) {
        count++;
      }
    }
    return count;
  }
}
