package com.slz.crm.quality;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.slz.crm.knowledge.retrieval.ImageSimilarityGate;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 近重复下限签收决议守卫（near-dup-floor-lowering，零外呼、纯 JVM）：签收档位、冻结带基准、纯加性边界与六份证据锚 SHA 的落成校验。判据 E1–E4/E5 见
 * {@code openspec/changes/near-dup-floor-lowering/proposal.md} §4。
 *
 * <p><b>核心不变量（故意的不相等）</b>：生产准入下限 {@code NEAR_DUPLICATE_FLOOR}(0.982) < 冻结带基准 {@code
 * BAND_REFERENCE_FLOOR}(0.9887)——生产准入面已下探到证据档之下，三档内边界由冻结基准推导、不随准入下限缩放。
 * 任何把两常量「顺手对齐」的改动（等价于把下探无声回滚或无声扩带）在这里红牌。
 */
class NearDupFloorSignoffGuardTest {

  private static final Path SIGNOFF_FILE =
      Path.of("docs", "rag-quality", "near-dup-floor-signoff-v1.json");

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static JsonNode signoff;

  @BeforeAll
  static void loadSignoff() throws Exception {
    assertTrue(Files.exists(SIGNOFF_FILE), "签收决议锚缺失: " + SIGNOFF_FILE.toAbsolutePath());
    signoff = MAPPER.readTree(Files.readAllBytes(SIGNOFF_FILE));
  }

  @Test
  @DisplayName("签收落成：signedFloor/previousFloor/bandReferenceFloor 与生产常量逐值一致")
  void signoffMatchesProductionConstants() {
    assertEquals(
        0.982D, signoff.path("signedFloor").asDouble(), 1e-12D, "signedFloor 必须是签收档 0.982");
    assertEquals(
        ImageSimilarityGate.NEAR_DUPLICATE_FLOOR,
        signoff.path("signedFloor").asDouble(),
        1e-12D,
        "生产准入下限必须等于签收档（生产常量不得偏离签收）");
    assertEquals(
        0.9887D, signoff.path("previousFloor").asDouble(), 1e-12D, "previousFloor 必须是改档前 0.9887");
    assertEquals(
        0.9887D,
        signoff.path("bandReferenceFloor").asDouble(),
        1e-12D,
        "bandReferenceFloor 必须是冻结基准 0.9887");
    assertEquals(
        ImageSimilarityGate.BAND_REFERENCE_FLOOR,
        signoff.path("bandReferenceFloor").asDouble(),
        1e-12D,
        "闸门带基准必须等于签收冻结基准");
    assertTrue(signoff.path("bandBasisFrozen").asBoolean(), "bandBasisFrozen 必须为 true");
    assertTrue(signoff.path("evidenceAnchorsFrozen").asBoolean(), "evidenceAnchorsFrozen 必须为 true");
  }

  @Test
  @DisplayName("故意的不相等：生产准入下限严格小于冻结带基准（防「顺手对齐」回滚下探）")
  void admissionFloorDeliberatelyBelowBandReferenceFloor() {
    // 0.982 < 0.9887 是 near-dup-floor-lowering 的核心语义：准入面下探到证据档之下，
    // 而三档内边界仍由 0.9887 推导。两者相等 = 下探被回滚；反转 = 语义错乱。都不是合法状态。
    assertTrue(
        ImageSimilarityGate.NEAR_DUPLICATE_FLOOR < ImageSimilarityGate.BAND_REFERENCE_FLOOR,
        "NEAR_DUPLICATE_FLOOR(0.982) 必须严格小于 BAND_REFERENCE_FLOOR(0.9887)："
            + "相等说明签收下探被回滚，反转说明两常量被错误改写（见 near-dup-floor-signoff-v1）");
    assertEquals(0.982D, ImageSimilarityGate.NEAR_DUPLICATE_FLOOR, 1e-12D, "准入下限漂移");
    assertEquals(0.9887D, ImageSimilarityGate.BAND_REFERENCE_FLOOR, 1e-12D, "带基准漂移");
  }

  @Test
  @DisplayName("纯加性：内边界冻结 0.9906/0.9925，新准入区 [0.982, 0.9887) 全落 NEAR_LOW")
  void bandBasisFrozenAndNewZoneAllLow() {
    double threshold = ImageSimilarityGate.DEFAULT_THRESHOLD;
    assertEquals(
        0.9906D, ImageSimilarityGate.lowBandUpperBound(threshold), 1e-12D, "低/中内边界必须冻结在 0.9906");
    assertEquals(
        0.9925D, ImageSimilarityGate.highBandLowerBound(threshold), 1e-12D, "中/高内边界必须冻结在 0.9925");
    for (double cosine : new double[] {0.982D, 0.985D, 0.988D, 0.9886D, 0.9886999D}) {
      assertEquals(
          ImageSimilarityGate.TIER_NEAR_DUPLICATE,
          ImageSimilarityGate.tierOf(cosine, threshold),
          "新准入区必须是近似档: " + cosine);
      assertEquals(
          ImageSimilarityGate.BAND_NEAR_LOW,
          ImageSimilarityGate.bandOf(cosine, threshold),
          "新准入区必须全落 NEAR_LOW（最重降权 0.5）: " + cosine);
      assertEquals(0.5D, ImageSimilarityGate.weightOfBand(ImageSimilarityGate.BAND_NEAR_LOW), 0D);
    }
  }

  @Test
  @DisplayName("位移窗口不变：W_max 仍 0.15（三档权重与融合权重零改动）")
  void displacementWindowUnchanged() {
    assertEquals(0.75D, ImageSimilarityGate.NEAR_DUPLICATE_WEIGHT_HIGH, 0D, "高档权重漂移");
    assertEquals(0.6D, ImageSimilarityGate.NEAR_DUPLICATE_WEIGHT_MID, 0D, "中档权重漂移");
    assertEquals(0.5D, ImageSimilarityGate.NEAR_DUPLICATE_WEIGHT_LOW, 0D, "低档权重漂移");
    // W_max = 0.30×(1−0.5)×1.0：图像路融合权重 × 最低档降幅 × 重排归一化上界（被放缩的是图像路 rerank 后分，值域 [0,1]）
    double wMax = 0.30D * (1D - ImageSimilarityGate.NEAR_DUPLICATE_WEIGHT_LOW) * 1.0D;
    assertEquals(0.15D, wMax, 1e-12D, "W_max 必须仍是 0.15");
  }

  @Test
  @DisplayName("六份证据锚 SHA-256 实算逐份相等（MessageDigest 真读文件真算，非硬编码自证）")
  void evidenceAnchorShasRecomputed() throws Exception {
    JsonNode anchors = signoff.path("evidenceAnchors");
    assertTrue(anchors.isObject() && anchors.size() == 6, "签收锚必须登记六份证据锚，实测: " + anchors.size());
    Iterator<Map.Entry<String, JsonNode>> fields = anchors.fields();
    while (fields.hasNext()) {
      Map.Entry<String, JsonNode> field = fields.next();
      Path file = Path.of("docs", "rag-quality", field.getKey());
      assertTrue(Files.exists(file), "证据锚不存在: " + file);
      assertEquals(
          field.getValue().asText(),
          sha256(Files.readAllBytes(file)),
          "证据锚 SHA-256 与签收登记值不一致（锚被改动？证据锚冻结是本卡硬线）: " + field.getKey());
    }
  }

  @Test
  @DisplayName("签收要素齐：signedOn/decisionOwner 非空 + 代价六格 + 被拒三档")
  void signoffFieldsPresent() {
    assertFalse(signoff.path("signedOn").asText().isBlank(), "signedOn 必须非空");
    assertFalse(signoff.path("decisionOwner").asText().isBlank(), "decisionOwner 必须非空");
    assertEquals("OWNER", signoff.path("decisionOwner").asText(), "decisionOwner 必须是 OWNER");
    JsonNode cost = signoff.path("cost");
    for (String field :
        List.of(
            "recallAtK",
            "sameDocSuppression",
            "deltaR",
            "deltaS_same",
            "extraSuppressedSameDoc",
            "exchangeRate")) {
      assertTrue(cost.path(field).isNumber(), "代价六格缺 " + field);
    }
    assertEquals(0.666667D, cost.path("recallAtK").asDouble(), 1e-12D, "recallAtK 必须是 0.982 档实值");
    assertEquals(
        8.333333D, cost.path("exchangeRate").asDouble(), 1e-12D, "ΔR/ΔS_same 必须是 0.982 档实值");
    JsonNode rejected = signoff.path("rejectedTiers");
    assertTrue(rejected.isArray() && rejected.size() >= 3, "被拒档位必须登记 0.9887/0.98/0.90 三项");
  }

  @Test
  @DisplayName("签收锚字节纪律：LF 行尾（文件内无 0d 0a）")
  void signoffFileIsLfOnly() throws Exception {
    byte[] bytes = Files.readAllBytes(SIGNOFF_FILE);
    for (int index = 0; index < bytes.length; index++) {
      assertTrue(bytes[index] != 0x0D, "签收锚必须 LF 行尾（发现 CR 字节 @ offset " + index + "）");
    }
  }

  /** SHA-256 十六进制小写（与签收锚登记口径一致）。 */
  private static String sha256(byte[] bytes) throws Exception {
    MessageDigest digest = MessageDigest.getInstance("SHA-256");
    StringBuilder out = new StringBuilder();
    for (byte item : digest.digest(bytes)) {
      out.append(Character.forDigit((item >> 4) & 0xF, 16));
      out.append(Character.forDigit(item & 0xF, 16));
    }
    return out.toString();
  }
}
