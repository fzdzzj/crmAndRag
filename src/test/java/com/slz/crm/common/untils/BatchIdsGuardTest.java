package com.slz.crm.common.untils;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.slz.crm.common.enumeration.ErrorCode;
import com.slz.crm.common.exiception.BaseException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.LongStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 批量 ID 统一闸单测（fix-backlog-batch3 任务 2.3）。
 *
 * <p>锁定四类边界：①null/空集/全 null 元素一律 {@code PARAM_EMPTY}(50001)；②distinct 后恰 500 放行、501 拒（{@code
 * PARAM_OUT_OF_RANGE}(50007) 且文案带上限）；③重复 id 不占额度（IN 列表的真实规模才是口径）；④上限常量本身钉在 500（选型依据见 {@link
 * BatchIdsGuard} 类注释，改动此常量必须同步改注释与 handoff 对照表）。
 */
@DisplayName("批量 ID 统一闸（空集/上限/去重口径）")
class BatchIdsGuardTest {

  @Test
  @DisplayName("上限常量钉在 500（改动须同步类注释选型依据）")
  void maxBatchIds_isFiveHundred() {
    assertEquals(500, BatchIdsGuard.MAX_BATCH_IDS);
  }

  @Test
  @DisplayName("null 入参 → 50001 PARAM_EMPTY")
  void check_null_throwsParamEmpty() {
    BaseException ex = assertThrows(BaseException.class, () -> BatchIdsGuard.check(null));
    assertEquals(ErrorCode.PARAM_EMPTY.getCode(), ex.getCode());
  }

  @Test
  @DisplayName("空集入参 → 50001 PARAM_EMPTY")
  void check_empty_throwsParamEmpty() {
    BaseException ex = assertThrows(BaseException.class, () -> BatchIdsGuard.check(List.of()));
    assertEquals(ErrorCode.PARAM_EMPTY.getCode(), ex.getCode());
  }

  @Test
  @DisplayName("全 null 元素 → 50001 PARAM_EMPTY（去重后 0 个可用 id，IN 列表会退化成非法 SQL）")
  void check_allNullElements_throwsParamEmpty() {
    List<Long> withNulls = new ArrayList<>();
    withNulls.add(null);
    withNulls.add(null);
    BaseException ex = assertThrows(BaseException.class, () -> BatchIdsGuard.check(withNulls));
    assertEquals(ErrorCode.PARAM_EMPTY.getCode(), ex.getCode());
  }

  @Test
  @DisplayName("恰 500 个不同 id → 放行")
  void check_exactlyFiveHundred_passes() {
    assertDoesNotThrow(() -> BatchIdsGuard.check(ids(500)));
  }

  @Test
  @DisplayName("501 个不同 id → 50007 PARAM_OUT_OF_RANGE，文案带上限 500")
  void check_fiveHundredAndOne_throwsParamOutOfRange() {
    BaseException ex = assertThrows(BaseException.class, () -> BatchIdsGuard.check(ids(501)));
    assertAll(
        () -> assertEquals(ErrorCode.PARAM_OUT_OF_RANGE.getCode(), ex.getCode()),
        () -> assertTrue(ex.getMessage().contains("500"), "上限文案必须写明 500：" + ex.getMessage()));
  }

  @Test
  @DisplayName("重复 id 不占额度：600 个同值 id 仍放行；501 个不同 id 夹在重复里仍拒")
  void check_duplicateIdsCountedOnce() {
    List<Long> allSame = new ArrayList<>();
    for (int i = 0; i < 600; i++) {
      allSame.add(7L);
    }
    assertDoesNotThrow(() -> BatchIdsGuard.check(allSame));

    List<Long> distinctPlusDuplicates = new ArrayList<>(ids(501));
    distinctPlusDuplicates.addAll(ids(501));
    BaseException ex =
        assertThrows(BaseException.class, () -> BatchIdsGuard.check(distinctPlusDuplicates));
    assertEquals(ErrorCode.PARAM_OUT_OF_RANGE.getCode(), ex.getCode());
  }

  private static List<Long> ids(int size) {
    return LongStream.range(0, size).boxed().toList();
  }
}
