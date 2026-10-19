package com.slz.crm.common.untils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.slz.crm.pojo.entity.CustomerContactEntity;
import com.slz.crm.server.config.ForeignKeyConfig;
import java.beans.Introspector;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 级联删除批量化下沉单测（fix-engineering-debt-loop-write-batch 任务 1.3）。
 *
 * <p>锁定 A-6：{@code logicalDeleteByCascadeBatch(Class, List&lt;Long&gt;)} 的级联部分复用 {@code
 * deleteCascade} 单趟跑全量 ids（每层仍是一次 IN），主表逻辑删除收敛为「单条 update + SET isDeleted=true + WHERE id IN
 * (ids)」；总 SQL 条数与 id 个数无关（旧逐 id 入口会是 N 倍）。单条入口 {@code logicalDeleteByCascade} 的 updateById 语义保留。
 *
 * <p>夹具：用真实 {@link ForeignKeyConfig#ALL_RELATIONS} 图动态建立 mapper mock 与 ApplicationContext，断言不依赖
 * 各表真实列名（列名解析走 MP TableInfo，缺失时退回属性名，两种情况下本类断言均成立）。
 */
@DisplayName("外键级联删除批量化（A-6）")
@SuppressWarnings({"rawtypes", "unchecked"})
class ForeignKeyDeleteUtilBatchTest {

  private static final String MAIN_MAPPER = "customerContactMapper";

  private Map<String, BaseMapper> mappers;
  private ForeignKeyDeleteUtil util;

  @BeforeEach
  void setUp() {
    newFixture();
  }

  @Test
  @DisplayName("级联单趟：SQL 条数与 id 个数无关（1 个 id 与 3 个 id 完全一致）")
  void batchCascade_queryCountIndependentOfIdCount() {
    util.logicalDeleteByCascadeBatch(CustomerContactEntity.class, List.of(1L));
    int oneId = totalInvocations();

    newFixture();
    util.logicalDeleteByCascadeBatch(CustomerContactEntity.class, List.of(1L, 2L, 3L));
    int threeIds = totalInvocations();

    assertTrue(oneId > 0, "级联路径必须真的发出 SQL");
    assertEquals(oneId, threeIds, "旧逐 id 入口会把级联与查询放大 3 倍，单趟实现必须与 id 个数无关");
  }

  @Test
  @DisplayName("主表逻辑删：单条 update，SET isDeleted=true + WHERE id IN (ids)")
  void batchCascade_mainRecordSingleInUpdate() {
    util.logicalDeleteByCascadeBatch(CustomerContactEntity.class, List.of(11L, 12L));

    BaseMapper main = mappers.get(MAIN_MAPPER);
    ArgumentCaptor<Object> entityCaptor = ArgumentCaptor.forClass(Object.class);
    ArgumentCaptor<UpdateWrapper> wrapperCaptor = ArgumentCaptor.forClass(UpdateWrapper.class);
    verify(main, times(1)).update(entityCaptor.capture(), wrapperCaptor.capture());

    assertInstanceOf(CustomerContactEntity.class, entityCaptor.getValue());
    assertEquals(
        Boolean.TRUE,
        ((CustomerContactEntity) entityCaptor.getValue()).getIsDeleted(),
        "SET 必须显式置 isDeleted=true");
    UpdateWrapper wrapper = wrapperCaptor.getValue();
    assertTrue(wrapper.getSqlSegment().contains("id IN"), "必须一次 IN 命中全部 id");
    assertTrue(wrapper.getParamNameValuePairs().containsValue(11L));
    assertTrue(wrapper.getParamNameValuePairs().containsValue(12L));

    // 旧实现的逐条主表更新必须彻底消失
    verify(main, never()).updateById(any());
  }

  @Test
  @DisplayName("主表逻辑删：重复 id 输入仍只发一条 update")
  void batchCascade_duplicateIds_stillSingleStatement() {
    util.logicalDeleteByCascadeBatch(CustomerContactEntity.class, List.of(11L, 11L));

    verify(mappers.get(MAIN_MAPPER), times(1)).update(any(), any());
  }

  @Test
  @DisplayName("单条入口保留：logicalDeleteByCascade 仍走 updateById")
  void singleCascade_stillUsesUpdateById() {
    util.logicalDeleteByCascade(CustomerContactEntity.class, 11L);

    BaseMapper main = mappers.get(MAIN_MAPPER);
    verify(main, times(1)).updateById(any());
    verify(main, never()).update(any(), any());
  }

  @Test
  @DisplayName("空入参：返回 0 且零数据库交互")
  void batchCascade_emptyIds_noDatabaseAccess() {
    assertEquals(
        Integer.valueOf(0), util.logicalDeleteByCascadeBatch(CustomerContactEntity.class, null));
    assertEquals(
        Integer.valueOf(0),
        util.logicalDeleteByCascadeBatch(CustomerContactEntity.class, List.of()));
    assertEquals(0, totalInvocations());
  }

  /** 建立一套与被测 util 等价的夹具（真实关联图 + 每个实体一个 BaseMapper mock） */
  private void newFixture() {
    mappers = new LinkedHashMap<>();
    GenericApplicationContext context = new GenericApplicationContext();
    for (Class<?> entityClass : cascadeGraph(CustomerContactEntity.class)) {
      String beanName = mapperBeanName(entityClass);
      BaseMapper mapper = mock(BaseMapper.class);
      mappers.put(beanName, mapper);
      context.getBeanFactory().registerSingleton(beanName, mapper);
      // 级联每层都取得到子 id（保证递归全展开）；写操作统一成功
      doAnswer(invocation -> List.of(900L)).when(mapper).selectObjs(any());
      when(mapper.delete(any())).thenReturn(1);
      when(mapper.update(any(), any())).thenReturn(1);
    }
    context.refresh();
    util = new ForeignKeyDeleteUtil();
    ReflectionTestUtils.setField(util, "applicationContext", context);
  }

  /** 统计全部 mock 上发生的调用次数（等价于 SQL 条数观测口径） */
  private int totalInvocations() {
    int total = 0;
    for (BaseMapper mapper : mappers.values()) {
      total += mockingDetails(mapper).getInvocations().size();
    }
    return total;
  }

  /** 真实外键图里从根实体可达的全部子实体（含根） */
  private static Set<Class<?>> cascadeGraph(Class<?> root) {
    Set<Class<?>> visited = new LinkedHashSet<>();
    Deque<Class<?>> queue = new ArrayDeque<>();
    visited.add(root);
    queue.add(root);
    while (!queue.isEmpty()) {
      Map<Class<?>, List<String>> relations = ForeignKeyConfig.ALL_RELATIONS.get(queue.poll());
      if (relations == null) {
        continue;
      }
      for (Class<?> child : relations.keySet()) {
        if (visited.add(child)) {
          queue.add(child);
        }
      }
    }
    return visited;
  }

  /** 与 ForeignKeyDeleteUtil#getMapperBeanName 同一命名口径 */
  private static String mapperBeanName(Class<?> entityClass) {
    return Introspector.decapitalize(entityClass.getSimpleName().replaceAll("Entity$", ""))
        + "Mapper";
  }
}
