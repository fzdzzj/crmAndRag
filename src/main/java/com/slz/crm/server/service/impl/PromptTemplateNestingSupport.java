package com.slz.crm.server.service.impl;

import com.slz.crm.common.enumeration.ErrorCode;
import com.slz.crm.common.exiception.BaseException;
import com.slz.crm.pojo.entity.PromptTemplateEntity;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 提示词模板嵌套引用协作类（add-prompt-template-nesting 任务 1.1）。
 *
 * <p>纯函数协作：负责 {@code {{> code}}} 引用抽取与骨架递归展开，从 {@link PromptTemplateServiceImpl} 抽出保证其薄与 类规模不超 PMD
 * NCSS 阈值（对齐 {@link PromptTemplateRenderSupport} 拆分先例）。无事务、无 Spring 依赖、零真实外呼。
 *
 * <p><b>展开语义（两遍法第一遍）</b>：render 时先骨架展开——把各层 {@code {{> code}}} 递归替换为子模板 content（子模板自身 {@code
 * {{var}}} 占位与更深引用原样保留入文本），原文本中 {{var}} 占位符层叠保留供变量校验与替换（第二遍）。引用一律取子模板 <b>当前启用版本</b>；不引入 {@code {{>
 * code@version}}} 指定版本语法（超范围）。
 *
 * <p><b>异常语义（零新增 ErrorCode）</b>：子模板缺失 → 92008 ID_NOT_EXISTS；子模板停用 → 92027 NOT_ENABLED；循环引用或 深度超上限 →
 * 50002 PARAM_FORMAT_ERROR。深度上限 {@value #MAX_NEST_DEPTH} 常量兜底防炸栈。
 */
final class PromptTemplateNestingSupport {

  /** 嵌套展开最大深度（防炸栈）；超过即 PARAM_FORMAT_ERROR。 */
  static final int MAX_NEST_DEPTH = 8;

  /** {@code {{> code}}} 引用语法：双大括号、{@code >}、空白、引用 code、可选空白、双大括号。 */
  private static final Pattern REFERENCE = Pattern.compile("\\{\\{>\\s+([^{}\\s]+?)\\s*}}");

  private PromptTemplateNestingSupport() {}

  /** 顶层/子模板 code → 子模板当前版本实体；不存在返回 null（缺失/停用语义由本类统一判定）。 */
  @FunctionalInterface
  interface TemplateResolver {
    PromptTemplateEntity resolve(String code);
  }

  /** content 是否含任意 {@code {{> code}}} 嵌套引用（无则模板走既有字节等价路径）。 */
  static boolean containsNesting(String content) {
    return content != null && REFERENCE.matcher(content).find();
  }

  /**
   * render 完整编排（两遍法）：无嵌套走既有单层路径逐字节零变化；有嵌套先骨架展开并累积主+各层子 variables 并集，再按并集校验 （required
   * 并集强制）与占位符替换。零真实外呼。
   *
   * @param template 主模板（已确认启用态）
   * @param provided 渲染变量键值表
   * @param resolver 子模板解析接缝（当前启用版本）
   * @param renderSupport {@link PromptTemplateRenderSupport}（变量解析/校验/替换复用，保持薄）
   */
  static String renderContent(
      PromptTemplateEntity template,
      Map<String, String> provided,
      TemplateResolver resolver,
      PromptTemplateRenderSupport renderSupport) {
    List<PromptTemplateRenderSupport.PromptTemplateVariable> definitions;
    String contentToRender;
    if (!containsNesting(template.getContent())) {
      definitions = renderSupport.parseVariableDefinitions(template.getVariables());
      contentToRender = template.getContent();
    } else {
      definitions =
          new ArrayList<>(renderSupport.parseVariableDefinitions(template.getVariables()));
      contentToRender =
          expandSkeleton(
              template.getCode(),
              template.getContent(),
              resolver,
              renderSupport::parseVariableDefinitions,
              definitions,
              new ArrayDeque<>());
    }
    renderSupport.rejectUndefined(definitions, provided);
    renderSupport.rejectMissingRequired(definitions, provided);
    return renderSupport.replacePlaceholders(contentToRender, provided);
  }

  /** 抽取 content 中引用的全部 code（去重保序）；无引用返回空集。用于 create/update 非阻断 WARN 钩子。 */
  static Set<String> referencedCodes(String content) {
    Set<String> codes = new LinkedHashSet<>();
    if (content != null) {
      Matcher m = REFERENCE.matcher(content);
      while (m.find()) {
        codes.add(m.group(1));
      }
    }
    return codes;
  }

  /** 返回 content 引用的 code 中当前缺失者（resolver 返回 null 即缺失），供 create/update 非阻断 WARN 留痕。 */
  static Set<String> missingReferences(String content, TemplateResolver resolver) {
    Set<String> missing = new LinkedHashSet<>();
    for (String ref : referencedCodes(content)) {
      if (resolver.resolve(ref) == null) {
        missing.add(ref);
      }
    }
    return missing;
  }

  /** create/update 非阻断 WARN：content 引用 code 当前缺失时记 JUL WARN（含模板 id 与缺失 code），不阻断保存。 */
  static void warnMissingReferences(
      Long templateId, String templateCode, String content, TemplateResolver resolver) {
    Set<String> missing = missingReferences(content, resolver);
    if (!missing.isEmpty()) {
      Logger.getLogger(PromptTemplateNestingSupport.class.getName())
          .warning(
              "提示词模板(id="
                  + templateId
                  + ", code="
                  + templateCode
                  + ") content 引用不存在的子模板 code="
                  + missing
                  + "（前向引用合法，保存放行）");
    }
  }

  /**
   * 骨架展开（两遍法第一遍）。
   *
   * @param rootCode 主模板 code（入栈防主模板自引用成环）
   * @param content 当前模板 content（首轮为主模板）
   * @param resolver 子模板解析（当前启用版本）
   * @param variableParser 子模板 variables JSON 解析（并集累积）
   * @param unionDefinitions 变量定义并集累积桶（调用方传入，主模板定义入桶后由本方法追加各层子模板定义）
   * @return 骨架展开后的文本：{@code {{> }} } 引用已全替换，仅剩 {@code {{var}}} 占位符层叠待变量替换（第二遍）
   */
  static String expandSkeleton(
      String rootCode,
      String content,
      TemplateResolver resolver,
      Function<String, java.util.List<PromptTemplateRenderSupport.PromptTemplateVariable>>
          variableParser,
      java.util.List<PromptTemplateRenderSupport.PromptTemplateVariable> unionDefinitions,
      java.util.Deque<String> stack) {
    return expandRecursive(rootCode, content, resolver, variableParser, unionDefinitions, stack, 0);
  }

  /** 递归展开内核：{@code depth} 为当前层嵌套深度（主模板为 0，每层 {{> }} 引用 +1）。 */
  private static String expandRecursive(
      String rootCode,
      String content,
      TemplateResolver resolver,
      Function<String, java.util.List<PromptTemplateRenderSupport.PromptTemplateVariable>>
          variableParser,
      java.util.List<PromptTemplateRenderSupport.PromptTemplateVariable> unionDefinitions,
      java.util.Deque<String> stack,
      int depth) {
    stack.push(rootCode);
    try {
      Matcher m = REFERENCE.matcher(content);
      StringBuilder sb = new StringBuilder(content.length());
      int last = 0;
      while (m.find()) {
        String code = m.group(1);
        if (stack.contains(code)) {
          throw new BaseException(ErrorCode.PARAM_FORMAT_ERROR, "提示词模板嵌套循环引用：" + code);
        }
        if (depth >= MAX_NEST_DEPTH) {
          throw new BaseException(ErrorCode.PARAM_FORMAT_ERROR, "提示词模板嵌套深度超过上限 " + MAX_NEST_DEPTH);
        }
        PromptTemplateEntity child = resolver.resolve(code);
        if (child == null) {
          throw new BaseException(ErrorCode.ID_NOT_EXISTS, "引用的子模板不存在：" + code);
        }
        if (child.getStatus() == null || child.getStatus() != 0) {
          throw new BaseException(ErrorCode.NOT_ENABLED, "引用的子模板已停用：" + code);
        }
        sb.append(content, last, m.start());
        last = m.end();
        unionDefinitions.addAll(variableParser.apply(child.getVariables()));
        stack.push(code);
        sb.append(
            expandRecursive(
                child.getCode(),
                child.getContent(),
                resolver,
                variableParser,
                unionDefinitions,
                stack,
                depth + 1));
        stack.pop();
      }
      sb.append(content, last, content.length());
      return sb.toString();
    } finally {
      stack.pop();
    }
  }
}
