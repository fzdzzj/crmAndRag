package com.slz.crm.quality;

import com.slz.crm.knowledge.entity.DocumentVectorChunkEntity;
import com.slz.crm.knowledge.retrieval.SparseChunkRow;
import com.slz.crm.server.mapper.DocumentVectorChunkMapper;
import java.lang.reflect.Array;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 真检索基准的测试侧 DocumentVectorChunkMapper 内存 double（run-baseline-ladder 任务组 2，0.1 决策落地）。
 *
 * <p>决策背景（openspec 验证记录 0.1）：{@code SparseRecallService}/{@code ContextBuilder} 对生产库的唯一 耦合是 {@link
 * DocumentVectorChunkMapper}（查 {@code document_vector_chunk} 快照表）。本类在 mapper 层用 内存快照替换
 * DB，注入后两条生产检索逻辑以<b>真实代码</b>运行，基准自包含、确定性、无 Docker 依赖， 且未触碰任何 {@code src/main}。
 *
 * <p>实现：以 {@link Proxy} 优雅实现整个 {@link DocumentVectorChunkMapper}（含继承的 BaseMapper 大量方法），
 * 仅对基准实际调用的方法给出语义，其余返回类型安全的空默认——避免手写几十个 BaseMapper 方法桩。
 *
 * <p>生产口径偏差（measurement note，见验证记录 0.1）：{@code fulltextSearch} 的 MySQL FULLTEXT(ngram) {@code
 * MATCH..AGAINST} 相关度由一段字符 bigram 重叠分近似；无 {@code JOIN uploaded_file} 授权收敛与软删 过滤（基准语料单一授权
 * KB、无软删场景）；真库 ngram 路径的 DB 级正确性已由 {@code SparseRecallServiceIT}/ {@code FlywayMigrationIT}（V6
 * 修复后绿）独立背书。
 */
final class InMemoryDocumentVectorChunkMapper {

  /** 列条件正则：{@code column = #{...MPGENVALn}} 等式。 */
  private static final Pattern COL_EQ =
      Pattern.compile("([\\w_]+)\\s*=\\s*#\\{([^}]*\\.(\\w+))\\}");

  /** 列条件正则：{@code column IN (#{...MPGENVALn},...)}。 */
  private static final Pattern COL_IN = Pattern.compile("([\\w_]+)\\s+IN\\s*\\(([^)]*)\\)");

  private InMemoryDocumentVectorChunkMapper() {}

  /** 由快照行集合构造内存 mapper double。 */
  static DocumentVectorChunkMapper create(List<DocumentVectorChunkEntity> rows) {
    InvocationHandler handler = new Handler(new ArrayList<>(rows));
    return (DocumentVectorChunkMapper)
        Proxy.newProxyInstance(
            DocumentVectorChunkMapper.class.getClassLoader(),
            new Class<?>[] {DocumentVectorChunkMapper.class},
            handler);
  }

  /** 由基准切片明细构造内存 mapper double（供 ContextBuilder 邻居查询；chunkId = {@code <key>-<i>}）。 */
  static DocumentVectorChunkMapper createFromChunks(
      List<RagBenchmarkDataPreparer.BenchmarkChunk> chunks) {
    Map<String, DocumentVectorChunkEntity> rows = new java.util.LinkedHashMap<>();
    for (RagBenchmarkDataPreparer.BenchmarkChunk chunk : chunks) {
      String documentId =
          "benchdoc-" + chunk.chunkId().substring(0, chunk.chunkId().lastIndexOf('-'));
      rows.putIfAbsent(
          chunk.chunkId(),
          entity(
              documentId,
              chunk.filename(),
              chunk.category(),
              chunk.chunkIndex(),
              chunk.text(),
              chunk.pageNo(),
              chunk.rowIndex()));
    }
    return create(new ArrayList<>(rows.values()));
  }

  /** 把检索候选的切片元数据组装成快照行（与 {@code RagBenchmarkDataPreparer} 的 metadata 同口径）。 */
  static DocumentVectorChunkEntity entity(
      String documentId,
      String filename,
      String category,
      int chunkIndex,
      String chunkText,
      Integer pageNo,
      Integer rowIndex) {
    DocumentVectorChunkEntity entity = new DocumentVectorChunkEntity();
    entity.setId((long) chunkIndex);
    entity.setDocumentId(documentId);
    entity.setChunkIndex(chunkIndex);
    entity.setChunkText(chunkText);
    entity.setChunkHash("bench-" + chunkIndex);
    entity.setFilename(filename);
    entity.setCategory(category);
    entity.setPageNo(pageNo);
    entity.setRowIndex(rowIndex);
    entity.setChunkRole("CHILD");
    return entity;
  }

  /** InvocationHandler：仅 fulltextSearch/selectById/selectBatchIds/selectList 有语义，其余走类型安全默认。 */
  private static final class Handler implements InvocationHandler {
    private final List<DocumentVectorChunkEntity> rows;

    Handler(List<DocumentVectorChunkEntity> rows) {
      this.rows = rows;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) {
      String name = method.getName();
      switch (name) {
        case "fulltextSearch":
          return fulltextSearch(
              (String) args[0], unchecked(args[1]), (String) args[2], (int) args[3]);
        case "selectById":
          return selectById(args.length > 0 ? args[0] : null);
        case "selectBatchIds":
          return selectBatchIds(args.length > 0 ? args[0] : null);
        case "selectList":
          return selectList(args.length > 0 ? args[0] : null);
        case "deletePhysicallyByDocumentId":
          return deletePhysicallyByDocumentId((String) args[0]);
        case "toString":
          return "InMemoryDocumentVectorChunkMapper(" + rows.size() + " rows)";
        case "hashCode":
          return System.identityHashCode(proxy);
        case "equals":
          return proxy == args[0];
        default:
          return defaultReturn(method.getReturnType());
      }
    }

    /** 整句字符 bigram 重叠分近似 FULLTEXT ngram；返回按相关度降序的切片行。 */
    @SuppressWarnings("unchecked")
    private List<SparseChunkRow> fulltextSearch(
        String query, List<String> kbIds, String category, int limit) {
      if (query == null || query.isBlank() || kbIds == null || kbIds.isEmpty() || limit <= 0) {
        return List.of();
      }
      Set<String> queryGrams = bigrams(query);
      if (queryGrams.isEmpty()) {
        return List.of();
      }
      List<SparseChunkRow> hits = new ArrayList<>();
      for (DocumentVectorChunkEntity entity : rows) {
        if (!"CHILD".equalsIgnoreCase(entity.getChunkRole())) {
          continue;
        }
        if (category != null && !category.isBlank() && !category.equals(entity.getCategory())) {
          continue;
        }
        Set<String> chunkGrams = bigrams(entity.getChunkText());
        double overlap = 0;
        for (String gram : queryGrams) {
          if (chunkGrams.contains(gram)) {
            overlap++;
          }
        }
        if (overlap <= 0) {
          continue;
        }
        double score = overlap / queryGrams.size();
        hits.add(toRow(entity, score));
      }
      hits.sort(
          (a, b) ->
              Double.compare(
                  b.getScore() == null ? 0 : b.getScore(),
                  a.getScore() == null ? 0 : a.getScore()));
      return hits.size() > limit ? hits.subList(0, limit) : hits;
    }

    private SparseChunkRow toRow(DocumentVectorChunkEntity entity, double score) {
      SparseChunkRow row = new SparseChunkRow();
      row.setChunkId(entity.getId());
      row.setDocumentId(entity.getDocumentId());
      row.setChunkIndex(entity.getChunkIndex());
      row.setChunkText(entity.getChunkText());
      row.setFilename(entity.getFilename());
      row.setCategory(entity.getCategory());
      row.setPageNo(entity.getPageNo());
      row.setRowIndex(entity.getRowIndex());
      row.setKnowledgeBaseId(null); // 追加字段：double 不建模授权 JOIN，调用方传入即唯一口径
      row.setScore(score);
      return row;
    }

    private DocumentVectorChunkEntity selectById(Object id) {
      if (id == null) {
        return null;
      }
      long target;
      if (id instanceof Number number) {
        target = number.longValue();
      } else {
        try {
          target = Long.parseLong(String.valueOf(id));
        } catch (NumberFormatException exception) {
          return null;
        }
      }
      for (DocumentVectorChunkEntity entity : rows) {
        if (entity.getId() != null && entity.getId() == target) {
          return entity;
        }
      }
      return null;
    }

    /** 批量主键查询（update-context-snapshot-batch-read）：返回集合内主键命中的行，语义与逐条 selectById 一致。 */
    private List<DocumentVectorChunkEntity> selectBatchIds(Object ids) {
      List<DocumentVectorChunkEntity> result = new ArrayList<>();
      if (ids instanceof Iterable<?> iterable) {
        for (Object id : iterable) {
          DocumentVectorChunkEntity entity = selectById(id);
          if (entity != null) {
            result.add(entity);
          }
        }
      }
      return result;
    }

    /** 解析 QueryWrapper 的 sqlSegment + 参数对，按列条件过滤；未识别列安全放行。 */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private List<DocumentVectorChunkEntity> selectList(Object wrapper) {
      if (!(wrapper
          instanceof com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<?> queryWrapper)) {
        return List.of();
      }
      Map<String, Object> params = new java.util.HashMap<>();
      try {
        Map<String, Object> raw = queryWrapper.getParamNameValuePairs();
        if (raw != null) {
          params.putAll(raw);
        }
      } catch (RuntimeException ignored) {
        // 读取失败则按无条件处理（保守：返回全量，检索右侧再按 hit 过滤）
      }
      String sql = wrapperSql(wrapper);
      return selectListBySql(queryWrapper, sql, rows, params);
    }

    private List<DocumentVectorChunkEntity> selectListBySql(
        com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<?> queryWrapper,
        String sql,
        List<DocumentVectorChunkEntity> rows,
        Map<String, Object> params) {
      List<Criterion> criteria = new ArrayList<>();
      List<String> orDocuments = new ArrayList<>();
      List<List<Object>> orIndexGroups = new ArrayList<>();
      Matcher inMatcher = COL_IN.matcher(sql);
      while (inMatcher.find()) {
        List<Object> values = new ArrayList<>();
        Matcher token = Pattern.compile("MPGENVAL\\d+").matcher(inMatcher.group(2));
        while (token.find()) {
          Object value = resolveParam(params, token.group());
          if (value instanceof Iterable<?> iterable) {
            iterable.forEach(values::add);
          } else if (value != null && value.getClass().isArray()) {
            int len = Array.getLength(value);
            for (int index = 0; index < len; index++) {
              values.add(Array.get(value, index));
            }
          } else if (value != null) {
            values.add(value);
          }
        }
        if ("chunk_index".equalsIgnoreCase(inMatcher.group(1).trim())) {
          orIndexGroups.add(values);
        }
        criteria.add(Criterion.of(inMatcher.group(1), values));
      }
      Matcher eqMatcher = COL_EQ.matcher(sql);
      while (eqMatcher.find()) {
        Object value = resolveParam(params, eqMatcher.group(3));
        if ("document_id".equalsIgnoreCase(eqMatcher.group(1).trim())) {
          orDocuments.add(value == null ? null : String.valueOf(value));
        }
        if (value != null) {
          criteria.add(Criterion.of(eqMatcher.group(1), List.of(value)));
        }
      }
      // 批读邻居查询按文档分组 OR 条件组：k 个 document_id 等值按出现序配对 k 个 chunk_index IN 组，
      // 语义 = 各组 (document_id, chunk_index 集合) 的 OR，而非全部条件的 AND——展开为单个 OR 组判据
      if (orDocuments.size() > 1 && orIndexGroups.size() == orDocuments.size()) {
        criteria.removeIf(
            criterion ->
                "document_id".equalsIgnoreCase(criterion.column())
                    || "chunk_index".equalsIgnoreCase(criterion.column()));
        criteria.add(Criterion.orGroups(orDocuments, orIndexGroups));
      }
      List<DocumentVectorChunkEntity> matched = new ArrayList<>();
      for (DocumentVectorChunkEntity entity : rows) {
        boolean ok = true;
        for (Criterion criterion : criteria) {
          if (!criterion.matches(entity)) {
            ok = false;
            break;
          }
        }
        if (ok) {
          matched.add(entity);
        }
      }
      return matched;
    }

    private String wrapperSql(Object wrapper) {
      try {
        return String.valueOf(
            ((com.baomidou.mybatisplus.core.conditions.AbstractWrapper<?, ?, ?>) wrapper)
                .getSqlSegment());
      } catch (ClassCastException ignored) {
        return "";
      }
    }

    /**
     * 参数解析：sqlSegment 引用 {@code ew.paramNameValuePairs.MPGENVALn}，键可能带 {@code
     * ew.paramNameValuePairs.} 前缀或裸名，这里按精确名或 {@code .<name>} 后缀解析，避免 {@code List.of(null)} 触发 NPE。
     */
    private static Object resolveParam(Map<String, Object> params, String tokenName) {
      if (params == null) {
        return null;
      }
      Object exact = params.get(tokenName);
      if (exact != null || params.containsKey(tokenName)) {
        return exact;
      }
      for (Map.Entry<String, Object> entry : params.entrySet()) {
        if (entry.getKey().endsWith("." + tokenName) || entry.getKey().equals(tokenName)) {
          return entry.getValue();
        }
      }
      return null;
    }

    private int deletePhysicallyByDocumentId(String documentId) {
      int before = rows.size();
      rows.removeIf(entity -> documentId != null && documentId.equals(entity.getDocumentId()));
      return before - rows.size();
    }

    private Object defaultReturn(Class<?> returnType) {
      if (returnType.isPrimitive()) {
        if (returnType == boolean.class) {
          return false;
        }
        if (returnType == int.class) {
          return 0;
        }
        if (returnType == long.class) {
          return 0L;
        }
        if (returnType == double.class) {
          return 0.0d;
        }
        return 0;
      }
      return null;
    }
  }

  /**
   * 解析出的单列过滤条件（或文档分组的 OR 组判据）。 {@code or_groups} 形态：第 i 组命中条件 = {@code document_id 等值 docs[i] 且
   * chunk_index ∈ indexGroups[i]}，整组判据为各组之 OR（其余列条件仍与之 AND）。
   */
  private record Criterion(String column, List<Object> values, List<List<Object>> indexGroups) {

    static Criterion of(String column, List<Object> values) {
      return new Criterion(column.trim().toLowerCase(Locale.ROOT), values, List.of());
    }

    static Criterion orGroups(List<String> documents, List<List<Object>> indexGroups) {
      return new Criterion("or_groups", List.copyOf(documents), List.copyOf(indexGroups));
    }

    boolean matches(DocumentVectorChunkEntity entity) {
      switch (column) {
        case "document_id":
          return values.contains(entity.getDocumentId());
        case "chunk_role":
          return values.contains(entity.getChunkRole());
        case "chunk_index":
          for (Object value : values) {
            if (value instanceof Number number
                && entity.getChunkIndex() != null
                && entity.getChunkIndex() == number.intValue()) {
              return true;
            }
          }
          return false;
        case "or_groups":
          for (int index = 0; index < values.size(); index++) {
            if (values.get(index).equals(entity.getDocumentId())
                && containsIndex(indexGroups.get(index), entity.getChunkIndex())) {
              return true;
            }
          }
          return false;
        default:
          // 未识别列：宽容放行（基准装配仅用到以上几列）
          return true;
      }
    }

    private static boolean containsIndex(List<Object> values, Integer chunkIndex) {
      if (chunkIndex == null) {
        return false;
      }
      for (Object value : values) {
        if (value instanceof Number number && number.intValue() == chunkIndex.intValue()) {
          return true;
        }
      }
      return false;
    }
  }

  /** 字符 bigram 集（小写，过滤空白；覆盖中文与 ASCII 词法）。 */
  static Set<String> bigrams(String text) {
    Set<String> grams = new HashSet<>();
    if (text == null || text.isEmpty()) {
      return grams;
    }
    StringBuilder compact = new StringBuilder();
    for (int index = 0; index < text.length(); index++) {
      char ch = text.charAt(index);
      if (Character.isLetterOrDigit(ch)) {
        compact.append(Character.toLowerCase(ch));
      }
    }
    for (int index = 0; index < compact.length() - 1; index++) {
      grams.add(compact.substring(index, index + 2));
    }
    return grams;
  }

  @SuppressWarnings("unchecked")
  private static List<String> unchecked(Object value) {
    return (List<String>) value;
  }
}
