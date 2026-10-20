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
 * <p>合法形态严格限定为「裸枚举常量」两种：{@code new BaseException(ErrorCode.X)} 与 {@code new
 * BaseException(ErrorCode.X, 原文案)}。{@code ErrorCode.X.getMessage()} 单参（本卡收敛前的 56 处形态）与 {@code new
 * BaseException()} 空参一律判违规——前者会让 {@code code} 再次丢失，后者连文案都没有；零豁免。
 *
 * <p>原待裁决 4 处（商业活动关联「已存在」）已于返场轮次按 Owner 拍板映射为 {@code PARAM_FORMAT_ERROR}(50002)，白名单随之清空，任何新增单
 * String 构造都会立刻红。
 */
@DisplayName("BaseException 单参构造器收敛门禁")
class BaseExceptionConvergenceTest {

  /** 允许的实参形态：裸枚举常量，或「裸枚举常量 + 自定义文案」；其余（含 .getMessage()、空参）均违规。 */
  private static final Pattern ENUM_CONSTRUCTOR_ARGS =
      Pattern.compile("ErrorCode\\.[A-Z0-9_]+(\\s*,\\s*.+)?");

  /**
   * 构造点定位：{@code new} 与类名之间、类名与左括号之间都允许出现空白。
   *
   * <p>本卡加固点：旧实现用字面量 {@code indexOf("new BaseException(")} 定位，遇到 {@code new BaseException (}
   * 这类「括号前多一个空格」的格式化形态会整体漏检（既不计数也不判违规），格式化工具回滚即可静默绕过门禁。
   */
  private static final Pattern CONSTRUCTOR_SITE = Pattern.compile("new\\s+BaseException\\s*\\(");

  @Test
  @DisplayName("src/main 不得再出现 BaseException 单 String 构造（零豁免）")
  void noSingleStringConstructorInMainSources() throws IOException {
    Path root = Paths.get("src/main/java");
    assertThat(Files.isDirectory(root)).as("源码根目录必须可定位: %s", root.toAbsolutePath()).isTrue();

    List<String> offenders = new ArrayList<>();
    int totalSites = 0;

    try (Stream<Path> files = Files.walk(root)) {
      for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
        String source = Files.readString(file, StandardCharsets.UTF_8);
        for (String args : constructorArgs(source.replaceAll("\\s+", " "))) {
          totalSites++;
          if (isConvergedArgs(args)) {
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

  @Test
  @DisplayName("自证：裸枚举常量（单参）与「枚举 + 原文案」（双参）判为合规")
  void acceptsBareEnumArgsConstructorForms() {
    assertThat(isConvergedArgs("ErrorCode.PARAM_EMPTY")).isTrue();
    assertThat(isConvergedArgs("ErrorCode.PARAM_FORMAT_ERROR, MessageConstant.X")).isTrue();
    assertThat(
            isConvergedArgs(
                "ErrorCode.ID_NOT_EXISTS, ErrorCode.ID_NOT_EXISTS.getMessage().formatted(\"客户\")"))
        .isTrue();
  }

  @Test
  @DisplayName("自证：.getMessage() 单参 / 空参 / 字符串形态一律判违规（重引入即红）")
  void rejectsLegacySingleStringArgsConstructorForms() {
    assertThat(isConvergedArgs("")).as("空参构造 new BaseException() 必须判违规").isFalse();
    assertThat(isConvergedArgs("ErrorCode.PARAM_EMPTY.getMessage()"))
        .as("正是本卡收敛前的 56 处形态，必须判违规")
        .isFalse();
    assertThat(isConvergedArgs("ErrorCode.ID_NOT_EXISTS.getMessage().formatted(\"客户\")"))
        .as(".formatted 变体同样不得放行")
        .isFalse();
    assertThat(isConvergedArgs("\"用户不存在，请重新登录\"")).as("字符串字面量必须判违规").isFalse();
    assertThat(isConvergedArgs("MessageConstant.BUSINESS_ACTIVITY_USER_DUPLICATE"))
        .as("MessageConstant 常量形态必须判违规")
        .isFalse();
    assertThat(isConvergedArgs("String.format(ErrorCode.EXCEL_FORMAT_ERROR.getMessage(), 1)"))
        .as("String.format 包裹的 .getMessage() 必须判违规")
        .isFalse();
  }

  /** 实参判定：唯一放行形态是裸枚举常量（可带自定义文案）；空参、字符串、.getMessage() 链式调用全部违规。 */
  private static boolean isConvergedArgs(String args) {
    return ENUM_CONSTRUCTOR_ARGS.matcher(args).matches();
  }

  /**
   * 抽取「已扁平化」源码中的全部 {@code BaseException} 构造点实参（多行写法扁平化后即单行）。
   *
   * <p>定位依赖 {@link #CONSTRUCTOR_SITE}，故 {@code new BaseException(} 与 {@code new BaseException (}
   * 两种形态都会计入扫描点；旧实现用字面量 indexOf 定位时后者整点消失。
   */
  private static List<String> constructorArgs(String flattened) {
    List<String> args = new ArrayList<>();
    Matcher matcher = CONSTRUCTOR_SITE.matcher(flattened);
    while (matcher.find()) {
      args.add(
          flattened.substring(matcher.end(), matchingParen(flattened, matcher.end() - 1)).trim());
    }
    return args;
  }

  @Test
  @DisplayName("自证：`new BaseException (` 空格形态同样被计入扫描点并判违规（格式化回滚防线）")
  void spacedConstructorFormIsScannedAndRejected() {
    String flattened =
        "throw new BaseException ( ErrorCode.PARAM_EMPTY.getMessage() );".replaceAll("\\s+", " ");
    assertThat(flattened)
        .as("旧实现按字面量 new BaseException( 定位，空格形态整点漏检")
        .doesNotContain("new BaseException(");
    assertThat(constructorArgs(flattened))
        .as("加固后空格形态必须被抽成一个构造点")
        .containsExactly("ErrorCode.PARAM_EMPTY.getMessage()");
    assertThat(isConvergedArgs(constructorArgs(flattened).get(0))).as("空格形态不得因漏检而静默放行").isFalse();

    // 合法形态带空格也必须被计数：避免「合法即不可见」的对称盲区
    List<String> legalSpaced = constructorArgs("new BaseException (ErrorCode.PARAM_EMPTY)");
    assertThat(legalSpaced).containsExactly("ErrorCode.PARAM_EMPTY");
    assertThat(isConvergedArgs(legalSpaced.get(0))).isTrue();

    // 同一行混合两种形态：旧实现只数得 1 个点位，加固后必须数得 2 个
    assertThat(constructorArgs("a(new BaseException(ErrorCode.A)); b(new BaseException (\"x\"));"))
        .containsExactly("ErrorCode.A", "\"x\"");
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
