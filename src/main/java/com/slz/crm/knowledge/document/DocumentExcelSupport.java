package com.slz.crm.knowledge.document;

import com.alibaba.excel.EasyExcel;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Excel 行级解析支持类（tighten-pmd-residual-325 任务 6.3 自 DocumentService 拆出，行为等价）。 按行保留 rowIndex，满足 D15
 * 来源锚点要求；表头投影与列名回退策略见各方法。
 */
final class DocumentExcelSupport {
  /** 表头启发式（add-excel-header-projection 任务 1.2）：非空格 ≥ 2 且每格长度 ≤ 32。 */
  private static final int HEADER_MIN_NON_EMPTY = 2;

  private static final int HEADER_CELL_MAX_LEN = 32;

  private DocumentExcelSupport() {}

  static ParsedDocument parseExcel(InputStream content) {
    List<Map<Integer, String>> rows = EasyExcel.read(content).headRowNumber(0).sheet().doReadSync();
    List<DocumentPage> pages = new ArrayList<>();
    if (!rows.isEmpty()) {
      Map<Integer, String> firstRow = rows.get(0);
      boolean projectHeaders = looksLikeHeaderRow(firstRow);
      Map<Integer, String> headers = projectHeaders ? buildHeaderNames(firstRow) : Map.of();

      int rowIndex = 0;
      for (Map<Integer, String> row : rows) {
        rowIndex++;
        if (projectHeaders && rowIndex == 1) {
          continue; // 列名已投影到数据行，独立表头块会占 top-K
        }
        String text = projectHeaders ? projectRow(headers, row) : normalizeRow(row);
        if (!text.isEmpty()) {
          pages.add(new DocumentPage(1, rowIndex, text));
        }
      }
    }
    return new ParsedDocument("excel", pages);
  }

  /** 表头启发式（add-excel-header-projection 任务 1.2）：非空格 ≥ 2 且每格长度 ≤ 32。 */
  private static boolean looksLikeHeaderRow(Map<Integer, String> row) {
    boolean result = row != null && !row.isEmpty();
    if (result) {
      int nonEmpty = 0;
      for (String raw : row.values()) {
        if (raw == null) {
          continue;
        }
        String value = raw.strip();
        if (value.isEmpty()) {
          continue;
        }
        if (value.length() > HEADER_CELL_MAX_LEN) {
          result = false;
          break;
        }
        nonEmpty++;
      }
      if (result) {
        result = nonEmpty >= HEADER_MIN_NON_EMPTY;
      }
    }
    return result;
  }

  /** 为表头行建立列下标 → 列名；空列名回退 {@code 列{1-based}}，重名加 {@code _2} 后缀。 */
  private static Map<Integer, String> buildHeaderNames(Map<Integer, String> headerRow) {
    Map<Integer, String> names = new HashMap<>();
    Map<String, Integer> occurrence = new HashMap<>();
    for (Integer col : headerRow.keySet().stream().sorted().toList()) {
      String raw = headerRow.get(col);
      String base = (raw == null || raw.strip().isEmpty()) ? ("列" + (col + 1)) : raw.strip();
      int seen = occurrence.merge(base, 1, Integer::sum);
      names.put(col, seen == 1 ? base : base + "_" + seen);
    }
    return names;
  }

  /**
   * 把列名投影进数据行（add-excel-header-projection 任务 1.1）：{@code 列名：值}，空值省略，tab 拼接。 数据行多出的无表头列回退 {@code
   * 列{1-based}}。
   */
  private static String projectRow(Map<Integer, String> headers, Map<Integer, String> row) {
    String result = "";
    if (row != null && !row.isEmpty()) {
      result =
          row.keySet().stream()
              .sorted()
              .map(
                  col -> {
                    String raw = row.get(col);
                    if (raw == null) {
                      return null;
                    }
                    String value = raw.strip();
                    if (value.isEmpty()) {
                      return null;
                    }
                    String name = headers.get(col);
                    if (name == null || name.isEmpty()) {
                      name = "列" + (col + 1);
                    }
                    return name + "：" + value;
                  })
              .filter(Objects::nonNull)
              .reduce((left, right) -> left + "\t" + right)
              .orElse("");
    }
    return result;
  }

  /** 升级前行为：单元格值 strip 后按列下标 tab 拼接（无列名）。 */
  private static String normalizeRow(Map<Integer, String> row) {
    String result = "";
    if (row != null && !row.isEmpty()) {
      result =
          row.keySet().stream()
              .sorted()
              .map(row::get)
              .filter(Objects::nonNull)
              .map(String::strip)
              .filter(value -> !value.isEmpty())
              .reduce((left, right) -> left + "\t" + right)
              .orElse("");
    }
    return result;
  }
}
