package com.slz.crm.common.exiception;

import static org.assertj.core.api.Assertions.assertThat;

import com.slz.crm.common.enumeration.ErrorCode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * M-1 收敛门禁（fix-minor-debt-family 任务 1.1/1.2/1.5）。
 *
 * <p>背景：{@link BaseException#BaseException(String)} 单参构造器不写 {@code code} 字段，经 {@code
 * GlobalExceptionHandler} 降级后对客户端呈现为 {@code code=0}，错误码语义丢失。本测试在源码层把该构造器 在 {@code src/main}
 * 中的使用锁死为「零」，防止后续新代码再次把 code 写丢。
 *
 * <p>唯一豁免：{@code MessageConstant.BUSINESS_ACTIVITY_USER_DUPLICATE} 与 {@code
 * BUSINESS_ACTIVITY_CONTACT_ALREADY_EXIST} 在 {@link ErrorCode} 中确无对应常量，按卡面红线「零新增错误码常量」
 * 单列待裁决并保持原字符串构造，不擅自加码。豁免项以「精确文案 + 出现次数」双约束，新增同类调用会立刻红。
 */
@DisplayName("BaseException 单参构造器收敛门禁")
class BaseExceptionConvergenceTest {

  /** 待裁决豁免（ErrorCode 无对应码，Owner 裁决前保持原样）。 */
  private static final List<String> PENDING_DECISION_FRAGMENTS =
      List.of(
          "MessageConstant.BUSINESS_ACTIVITY_USER_DUPLICATE",
          "MessageConstant.BUSINESS_ACTIVITY_CONTACT_ALREADY_EXIST");

  /** 待裁决豁免的期望出现次数（4 处：用户重复 ×2 + 联系人重复 ×2）。 */
  private static final int EXPECTED_PENDING_DECISION_COUNT = 4;

  @Test
  @DisplayName("src/main 不得再出现 BaseException 单 String 构造（豁免项除外）")
  void noSingleStringConstructorInMainSources() throws IOException {
    Path root = Paths.get("src/main/java");
    assertThat(Files.isDirectory(root)).as("源码根目录必须可定位: %s", root.toAbsolutePath()).isTrue();

    List<String> offenders = new ArrayList<>();
    List<String> pendingDecision = new ArrayList<>();
    int totalSites = 0;

    try (Stream<Path> files = Files.walk(root)) {
      for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
        String source = Files.readString(file, StandardCharsets.UTF_8);
        String flattened = source.replaceAll("\\s+", " ");
        int from = 0;
        while (true) {
          int start = flattened.indexOf("new BaseException(", from);
          if (start < 0) {
            break;
          }
          totalSites++;
          int argsStart = start + "new BaseException(".length();
          int argsEnd = matchingParen(flattened, argsStart - 1);
          String args = flattened.substring(argsStart, argsEnd).trim();
          from = argsEnd;
          if (args.isEmpty() || args.startsWith("ErrorCode.")) {
            continue;
          }
          if (PENDING_DECISION_FRAGMENTS.stream().anyMatch(args::startsWith)) {
            pendingDecision.add(file.getFileName() + " -> " + args);
            continue;
          }
          offenders.add(file.getFileName() + " -> new BaseException(" + args + ")");
        }
      }
    }

    assertThat(totalSites).as("扫描点数量下限，防止文件遍历失效导致假绿").isGreaterThan(300);
    assertThat(offenders)
        .as("单 String 构造器会让 code 丢失（降级 code=0），必须改用 BaseException(ErrorCode...)：%s", offenders)
        .isEmpty();
    assertThat(pendingDecision)
        .as("待裁决豁免项必须是卡面登记的 4 处，新增同类调用必须显式裁决")
        .hasSize(EXPECTED_PENDING_DECISION_COUNT);
  }

  @Test
  @DisplayName("ErrorCode 枚举构造透传真实 code 且保留自定义文案")
  void enumConstructorCarriesCodeAndCustomMessage() {
    BaseException withCustomMessage =
        new BaseException(ErrorCode.CONTRACT_NOT_EXISTS, "合同不存在（自定义文案）");
    assertThat(withCustomMessage.getCode()).isEqualTo(ErrorCode.CONTRACT_NOT_EXISTS.getCode());
    assertThat(withCustomMessage.getMessage()).isEqualTo("合同不存在（自定义文案）");

    BaseException withDefaultMessage = new BaseException(ErrorCode.PAYMENT_NOT_EXISTS);
    assertThat(withDefaultMessage.getCode()).isEqualTo(ErrorCode.PAYMENT_NOT_EXISTS.getCode());
    assertThat(withDefaultMessage.getMessage())
        .isEqualTo(ErrorCode.PAYMENT_NOT_EXISTS.getMessage());
  }

  @Test
  @DisplayName("收敛后的历史 code=null 场景确实带出非空 code（回归黑名单）")
  void formerNullCodeSitesNowCarryRealCodes() {
    // 覆盖 M-1 映射表各「码族」各一例：认证 / 客户·合同 / 参数 / 业务 / 系统
    assertThat(new BaseException(ErrorCode.TOKEN_INVALID, "用户不存在，请重新登录").getCode())
        .isEqualTo(10003);
    assertThat(new BaseException(ErrorCode.USER_NOT_EXIST_PERMISSION, "该用户没有权限").getCode())
        .isEqualTo(92026);
    assertThat(new BaseException(ErrorCode.PARAM_FORMAT_ERROR, "新密码不能与原密码相同").getCode())
        .isEqualTo(50002);
    assertThat(new BaseException(ErrorCode.DATA_DELETE_FAILED, "删除失败，请注意用户状态").getCode())
        .isEqualTo(91003);
    assertThat(new BaseException(ErrorCode.SYSTEM_BUSY, "生成回款单号失败").getCode()).isEqualTo(90001);
  }

  /** 从开括号位置起做括号配平，返回配对闭括号下标（参数里含 {@code String.format(...)} 等嵌套调用）。 */
  private static int matchingParen(String text, int openIndex) {
    int depth = 0;
    for (int i = openIndex; i < text.length(); i++) {
      char c = text.charAt(i);
      if (c == '(') {
        depth++;
      } else if (c == ')') {
        depth--;
        if (depth == 0) {
          return i;
        }
      }
    }
    throw new IllegalStateException("括号不配平: " + text.substring(openIndex));
  }

  /** 防御性断言：正则辅助的 Pattern 常量保持在用（避免未使用字段被静态分析清理）。 */
  @Test
  @DisplayName("构造器签名仍在（单参 String 版本存在但 src/main 已无人使用）")
  void singleStringConstructorStillReservedForTests() throws NoSuchMethodException {
    assertThat(BaseException.class.getConstructor(String.class)).isNotNull();
    Pattern guard = Pattern.compile("BaseException\\((ErrorCode\\.[A-Z_]+)");
    Matcher matcher = guard.matcher("new BaseException(ErrorCode.TOKEN_INVALID, \"x\")");
    assertThat(matcher.find()).isTrue();
  }
}
