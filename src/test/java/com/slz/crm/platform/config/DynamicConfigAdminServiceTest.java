package com.slz.crm.platform.config;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.slz.crm.common.enumeration.DataScopeLevel;
import com.slz.crm.common.exiception.ServiceException;
import com.slz.crm.platform.config.audit.DynamicConfigAuditEvent;
import com.slz.crm.platform.config.audit.DynamicConfigAuditRecorder;
import com.slz.crm.platform.config.entity.DynamicConfigHistoryEntity;
import com.slz.crm.platform.config.entity.DynamicConfigItemEntity;
import com.slz.crm.platform.config.mapper.DynamicConfigHistoryMapper;
import com.slz.crm.platform.config.mapper.DynamicConfigItemMapper;
import com.slz.crm.platform.config.service.DynamicConfigAdminService;
import com.slz.crm.platform.contract.PlatformErrorCode;
import com.slz.crm.platform.contract.UserContext;
import com.slz.crm.platform.contract.UserContextHolder;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 动态配置管理服务测试：越权写拒绝 / 非法值拒绝保持原值 / 版本与历史 / 回滚 /
 * 软删恢复默认与复活 / 敏感值掩码 / 审计记录 / 写后热生效。
 */
class DynamicConfigAdminServiceTest {

    private DynamicConfigItemMapper itemMapper;
    private DynamicConfigHistoryMapper historyMapper;
    private DynamicConfigKeyRegistry registry;
    private DynamicConfigCache cache;
    private DynamicConfigAdminService service;
    private DynamicConfigAuditRecorder recorder;
    private final List<DynamicConfigAuditEvent> auditEvents = new ArrayList<>();
    private final List<DynamicConfigHistoryEntity> historyLog = new ArrayList<>();
    private final AtomicReference<DynamicConfigItemEntity> currentRow = new AtomicReference<>();
    private final AtomicInteger updateAffected = new AtomicInteger(1);

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        // 单元测试无 Spring 上下文：手动初始化 MP 表元信息（否则 LambdaQueryWrapper 拿不到列映射）
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                DynamicConfigItemEntity.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                DynamicConfigHistoryEntity.class);

        itemMapper = mock(DynamicConfigItemMapper.class);
        historyMapper = mock(DynamicConfigHistoryMapper.class);
        registry = new DynamicConfigKeyRegistry(new ObjectMapper());
        DynamicConfigProperties props = new DynamicConfigProperties();
        props.setRefreshIntervalMs(60_000L);
        cache = new DynamicConfigCache(itemMapper, registry, props);

        // 简易行级内存库：selectOne 按“是否过滤 is_deleted”区分活动行；update 结果可编程
        when(itemMapper.selectOne(any())).thenAnswer(inv -> {
            DynamicConfigItemEntity row = currentRow.get();
            if (row == null) {
                return null;
            }
            String sql = ((LambdaQueryWrapper<?>) inv.getArgument(0)).getSqlSegment();
            if (sql.contains("is_deleted") && Boolean.TRUE.equals(row.getIsDeleted())) {
                return null;
            }
            return row;
        });
        when(itemMapper.selectList(any())).thenAnswer(inv -> {
            DynamicConfigItemEntity row = currentRow.get();
            return row == null || Boolean.TRUE.equals(row.getIsDeleted())
                    ? List.of() : List.of(row);
        });
        when(itemMapper.update(isNull(), any())).thenAnswer(inv -> updateAffected.get());
        doAnswer(inv -> {
            DynamicConfigItemEntity entity = inv.getArgument(0);
            currentRow.set(entity);
            return 1;
        }).when(itemMapper).insert(any());

        doAnswer(inv -> {
            historyLog.add(inv.getArgument(0));
            return 1;
        }).when(historyMapper).insert(any());
        when(historyMapper.selectOne(any())).thenAnswer(inv -> {
            LambdaQueryWrapper<DynamicConfigHistoryEntity> w = inv.getArgument(0);
            w.getSqlSegment(); // 先渲染 SQL 段，参数占位才会填充进 paramNameValuePairs
            // 按参数值匹配（不依赖 MP 内部参数命名）：eq(config_key, key) + eq(version, N)
            var params = w.getParamNameValuePairs().values();
            Object key = params.stream().filter(String.class::isInstance).findFirst().orElse(null);
            Object ver = params.stream().filter(Integer.class::isInstance).findFirst().orElse(null);
            return historyLog.stream()
                    .filter(h -> h.getConfigKey().equals(key) && h.getVersion().equals(ver))
                    .findFirst().orElse(null);
        });

        recorder = mock(DynamicConfigAuditRecorder.class);
        doAnswer(inv -> {
            auditEvents.add(inv.getArgument(0));
            return null;
        }).when(recorder).record(any());
        ObjectProvider<DynamicConfigAuditRecorder> auditProvider = mock(ObjectProvider.class);
        when(auditProvider.getIfAvailable(any())).thenReturn(recorder);

        service = new DynamicConfigAdminService(itemMapper, historyMapper, registry, cache,
                new CurrentUserResolver(), auditProvider);
    }

    @AfterEach
    void tearDown() {
        UserContextHolder.clear();
        com.slz.crm.common.untils.BaseUnit.removeCurrentId();
    }

    private static UserContext admin() {
        return new UserContext(1L, 1L, 1L, DataScopeLevel.ALL, "超级管理员");
    }

    private static UserContext normalUser() {
        return new UserContext(2L, 2L, 2L, DataScopeLevel.SELF, "普通用户");
    }

    private DynamicConfigItemEntity row(String key, String value, int version) {
        DynamicConfigItemEntity e = new DynamicConfigItemEntity();
        e.setId(1L);
        e.setConfigKey(key);
        e.setNamespace("rag.retrieval");
        e.setValueType("INTEGER");
        e.setConfigValue(value);
        e.setVersion(version);
        e.setIsDeleted(false);
        e.setUpdatedBy("user:1");
        e.setCreateTime(LocalDateTime.now());
        e.setUpdateTime(LocalDateTime.now());
        return e;
    }

    // ==================== 越权 ====================

    @Test
    @DisplayName("非超管写被拒（FORBIDDEN），零 DB 写入零历史零审计")
    void nonSuperAdminWriteRejected() {
        UserContextHolder.runWith(normalUser(), () -> assertThatThrownBy(() ->
                service.updateValue("rag.retrieval.topK", "8", ""))
                .isInstanceOf(ServiceException.class)
                .extracting(e -> ((ServiceException) e).getCode())
                .isEqualTo(PlatformErrorCode.FORBIDDEN.getCode()));
        verify(itemMapper, never()).insert(any());
        verify(itemMapper, never()).update(isNull(), any());
        verify(historyMapper, never()).insert(any());
        assertThat(auditEvents).isEmpty();
    }

    @Test
    @DisplayName("未登录写被拒（UNAUTHORIZED）")
    void anonymousWriteRejected() {
        assertThatThrownBy(() -> service.updateValue("rag.retrieval.topK", "8", ""))
                .isInstanceOf(ServiceException.class)
                .extracting(e -> ((ServiceException) e).getCode())
                .isEqualTo(PlatformErrorCode.UNAUTHORIZED.getCode());
    }

    @Test
    @DisplayName("非超管读（列表/历史/回滚/刷新）同样被拒")
    void nonSuperAdminReadRejected() {
        UserContextHolder.runWith(normalUser(), () -> {
            assertThatThrownBy(() -> service.listItems("rag.retrieval"))
                    .isInstanceOf(ServiceException.class)
                    .extracting(e -> ((ServiceException) e).getCode())
                    .isEqualTo(PlatformErrorCode.FORBIDDEN.getCode());
            assertThatThrownBy(() -> service.history("rag.retrieval.topK"))
                    .isInstanceOf(ServiceException.class)
                    .extracting(e -> ((ServiceException) e).getCode())
                    .isEqualTo(PlatformErrorCode.FORBIDDEN.getCode());
            assertThatThrownBy(() -> service.refreshCache())
                    .isInstanceOf(ServiceException.class)
                    .extracting(e -> ((ServiceException) e).getCode())
                    .isEqualTo(PlatformErrorCode.FORBIDDEN.getCode());
        });
    }

    // ==================== 校验护栏 ====================

    @Test
    @DisplayName("非法值拒绝并保持原值：不落库、不写历史、不记审计、缓存不变")
    void invalidValueRejectedKeepingOriginal() {
        currentRow.set(row("rag.retrieval.topK", "8", 1));
        // 先预热缓存（读到旧值 8）
        UserContextHolder.runWith(admin(), () -> {
            assertThat(cache.typedValue("rag.retrieval.topK")).isEqualTo(8);
            assertThatThrownBy(() -> service.updateValue("rag.retrieval.topK", "-1", "越界"))
                    .isInstanceOf(ServiceException.class)
                    .extracting(e -> ((ServiceException) e).getCode())
                    .isEqualTo(PlatformErrorCode.VALIDATION.getCode());
            assertThatThrownBy(() -> service.updateValue("rag.retrieval.topK", "abc", "类型错"))
                    .isInstanceOf(ServiceException.class)
                    .extracting(e -> ((ServiceException) e).getCode())
                    .isEqualTo(PlatformErrorCode.VALIDATION.getCode());
            // 未知键拒绝
            assertThatThrownBy(() -> service.updateValue("foo.bar", "1", ""))
                    .isInstanceOf(ServiceException.class)
                    .extracting(e -> ((ServiceException) e).getCode())
                    .isEqualTo(PlatformErrorCode.VALIDATION.getCode());
            // 原值保持：缓存仍为 8
            assertThat(cache.typedValue("rag.retrieval.topK")).isEqualTo(8);
        });
        verify(itemMapper, never()).update(isNull(), any());
        verify(historyMapper, never()).insert(any());
        assertThat(auditEvents).isEmpty();
    }

    // ==================== 创建 / 更新 / 版本 / 热生效 ====================

    @Test
    @DisplayName("超管首次写入：version=1 + CREATE 历史 + 审计 + 立即热生效")
    void superAdminCreateWithHistoryAuditAndHotEffect() {
        UserContextHolder.runWith(admin(), () -> {
            ConfigItemView view = service.updateValue("rag.retrieval.topK", "8", "调高召回");
            assertThat(view.version()).isEqualTo(1);
            assertThat(view.value()).isEqualTo("8");
            assertThat(view.namespace()).isEqualTo("rag.retrieval");

            assertThat(historyLog).hasSize(1);
            assertThat(historyLog.get(0).getOperationType()).isEqualTo("CREATE");
            assertThat(historyLog.get(0).getVersion()).isEqualTo(1);
            assertThat(historyLog.get(0).getOldValue()).isNull();
            assertThat(historyLog.get(0).getNewValue()).isEqualTo("8");
            assertThat(historyLog.get(0).getOperatorRef()).isEqualTo("user:1");

            assertThat(auditEvents).hasSize(1);
            assertThat(auditEvents.get(0).operationType()).isEqualTo("CREATE");
            assertThat(auditEvents.get(0).operatorRef()).isEqualTo("user:1");
            assertThat(auditEvents.get(0).key()).isEqualTo("rag.retrieval.topK");

            // 热生效：写后读取契约立即返回新值（无需等刷新窗口）
            assertThat(readTopK()).isEqualTo(8);
        });
    }

    @Test
    @DisplayName("更新已有项：版本 +1、UPDATE 历史（旧值→新值）、审计、写后即时热生效")
    void updateExistingItemVersionsAndHotReloads() {
        currentRow.set(row("rag.retrieval.topK", "8", 1));
        UserContextHolder.runWith(admin(), () -> {
            // 预热缓存（旧值 8），随后更新为 6
            assertThat(cache.typedValue("rag.retrieval.topK")).isEqualTo(8);
            ConfigItemView view = service.updateValue("rag.retrieval.topK", "6", "收紧召回");
            assertThat(view.version()).isEqualTo(2);
            assertThat(view.value()).isEqualTo("6");

            // 模拟 DB 落库结果后：写后失效 → 立即读到新值（热生效，无需等刷新窗口）
            currentRow.get().setConfigValue("6");
            currentRow.get().setVersion(2);
            assertThat(readTopK()).isEqualTo(6);

            assertThat(historyLog).hasSize(1);
            assertThat(historyLog.get(0).getOperationType()).isEqualTo("UPDATE");
            assertThat(historyLog.get(0).getVersion()).isEqualTo(2);
            assertThat(historyLog.get(0).getOldValue()).isEqualTo("8");
            assertThat(historyLog.get(0).getNewValue()).isEqualTo("6");

            assertThat(auditEvents).hasSize(1);
            assertThat(auditEvents.get(0).operationType()).isEqualTo("UPDATE");
            assertThat(auditEvents.get(0).oldValue()).isEqualTo("8");
            assertThat(auditEvents.get(0).newValue()).isEqualTo("6");
        });
    }

    @Test
    @DisplayName("相同值重复提交为幂等成功：不写历史不记审计")
    void sameValueUpdateIsIdempotent() {
        currentRow.set(row("rag.retrieval.topK", "8", 1));
        UserContextHolder.runWith(admin(), () -> {
            ConfigItemView view = service.updateValue("rag.retrieval.topK", "8", "重复提交");
            assertThat(view.version()).isEqualTo(1);
            assertThat(historyLog).isEmpty();
            assertThat(auditEvents).isEmpty();
        });
    }

    @Test
    @DisplayName("乐观锁冲突：并发修改后写被拒并提示刷新")
    void optimisticLockConflictRejected() {
        currentRow.set(row("rag.retrieval.topK", "8", 1));
        updateAffected.set(0);
        UserContextHolder.runWith(admin(), () -> assertThatThrownBy(() ->
                service.updateValue("rag.retrieval.topK", "6", ""))
                .isInstanceOf(ServiceException.class)
                .extracting(e -> ((ServiceException) e).getCode())
                .isEqualTo(PlatformErrorCode.INTERNAL.getCode()));
    }

    // ==================== 回滚 ====================

    @Test
    @DisplayName("回滚到历史版本：恢复该版本值、版本 +1、ROLLBACK 历史与审计、热生效")
    void rollbackRestoresHistoricalValue() {
        currentRow.set(row("rag.retrieval.topK", "6", 2));
        // 历史：v1=8（CREATE）→ v2=6（UPDATE）
        historyLog.add(history("rag.retrieval.topK", 1, "CREATE", null, "8"));
        historyLog.add(history("rag.retrieval.topK", 2, "UPDATE", "8", "6"));

        UserContextHolder.runWith(admin(), () -> {
            assertThat(cache.typedValue("rag.retrieval.topK")).isEqualTo(6);
            ConfigItemView view = service.rollback("rag.retrieval.topK", 1, null);
            assertThat(view.version()).isEqualTo(3);
            assertThat(view.value()).isEqualTo("8");

            currentRow.get().setConfigValue("8");
            currentRow.get().setVersion(3);
            assertThat(readTopK()).isEqualTo(8);

            assertThat(historyLog).hasSize(3);
            DynamicConfigHistoryEntity rollback = historyLog.get(2);
            assertThat(rollback.getOperationType()).isEqualTo("ROLLBACK");
            assertThat(rollback.getVersion()).isEqualTo(3);
            assertThat(rollback.getOldValue()).isEqualTo("6");
            assertThat(rollback.getNewValue()).isEqualTo("8");
            assertThat(rollback.getRemark()).isEqualTo("回滚到版本 1");

            assertThat(auditEvents).hasSize(1);
            assertThat(auditEvents.get(0).operationType()).isEqualTo("ROLLBACK");
            assertThat(auditEvents.get(0).oldValue()).isEqualTo("6");
            assertThat(auditEvents.get(0).newValue()).isEqualTo("8");
        });
    }

    @Test
    @DisplayName("回滚到不存在的版本 / 当前及以后版本被拒")
    void rollbackInvalidTargetRejected() {
        currentRow.set(row("rag.retrieval.topK", "6", 2));
        historyLog.add(history("rag.retrieval.topK", 1, "CREATE", null, "8"));
        UserContextHolder.runWith(admin(), () -> {
            assertThatThrownBy(() -> service.rollback("rag.retrieval.topK", 99, null))
                    .isInstanceOf(ServiceException.class)
                    .extracting(e -> ((ServiceException) e).getCode())
                    .isEqualTo(PlatformErrorCode.VALIDATION.getCode());
            assertThatThrownBy(() -> service.rollback("rag.retrieval.topK", 2, null))
                    .isInstanceOf(ServiceException.class)
                    .extracting(e -> ((ServiceException) e).getCode())
                    .isEqualTo(PlatformErrorCode.VALIDATION.getCode());
        });
        verify(historyMapper, never()).insert(any());
        assertThat(auditEvents).isEmpty();
    }

    // ==================== 软删（恢复默认）/ 复活 ====================

    @Test
    @DisplayName("软删=恢复默认：读取回退默认，历史留痕；再次写入=复活")
    void deleteRestoresDefaultThenRevive() {
        currentRow.set(row("rag.retrieval.topK", "8", 1));
        UserContextHolder.runWith(admin(), () -> {
            assertThat(cache.typedValue("rag.retrieval.topK")).isEqualTo(8);
            ConfigItemView deleted = service.deleteOverride("rag.retrieval.topK", "恢复默认");
            assertThat(deleted.deleted()).isTrue();
            assertThat(deleted.value()).isNull();
            assertThat(deleted.version()).isEqualTo(2);

            // 软删后：活动读取回退默认（null），历史保留
            currentRow.get().setIsDeleted(true);
            currentRow.get().setVersion(2);
            assertThat(cache.typedValue("rag.retrieval.topK")).isNull();
            assertThat(historyLog.get(0).getOperationType()).isEqualTo("DELETE");

            // 复活：同一唯一键反删 + 新值，版本连续
            ConfigItemView revived = service.updateValue("rag.retrieval.topK", "6", "重新开启");
            assertThat(revived.version()).isEqualTo(3);
            currentRow.get().setIsDeleted(false);
            currentRow.get().setConfigValue("6");
            currentRow.get().setVersion(3);
            assertThat(readTopK()).isEqualTo(6);
            assertThat(historyLog.get(1).getOperationType()).isEqualTo("REVIVE");
            assertThat(auditEvents).hasSize(2);
            assertThat(auditEvents.get(0).operationType()).isEqualTo("DELETE");
            assertThat(auditEvents.get(1).operationType()).isEqualTo("REVIVE");
        });
    }

    // ==================== 敏感值掩码 ====================

    @Test
    @DisplayName("敏感键：管理端读取与审计均掩码，消费读取仍返回真实值")
    void sensitiveValueMaskedInAdminViewsAndAudit() {
        // 注册一个敏感测试键（密钥类本域不承载，此键仅用于验证掩码护栏）
        registry.register(List.of(new ConfigKeyDefinition(
                "business.test.webhookSecret", "business", ConfigValueType.STRING, "",
                "测试用敏感键", null, null, Set.of(), true,
                200, ConfigKeyDefinition.DEFAULT_MAX_VALUE_LENGTH, ConfigKeyDefinition.DEFAULT_MAX_VALUE_LENGTH,
                new ObjectMapper())));

        currentRow.set(row("business.test.webhookSecret", "sk-live-123456", 1));
        currentRow.get().setSensitive(true);
        UserContextHolder.runWith(admin(), () -> {
            ConfigItemView view = service.getItem("business.test.webhookSecret");
            assertThat(view.sensitive()).isTrue();
            assertThat(view.value()).isEqualTo("******");
            assertThat(view.value()).doesNotContain("123456");

            // 审计事件同样掩码（服务层本地行已被置为 v2/sk-live-999999）
            service.updateValue("business.test.webhookSecret", "sk-live-999999", "轮换");
            assertThat(auditEvents.get(0).operationType()).isEqualTo("UPDATE");
            assertThat(auditEvents.get(0).newValue()).isEqualTo("******");
            assertThat(auditEvents.get(0).newValue()).doesNotContain("999999");

            // 历史行存真实值（回滚需要），管理端视图/审计才掩码
            historyLog.add(history("business.test.webhookSecret", 1, "CREATE", null, "sk-live-123456"));
            service.rollback("business.test.webhookSecret", 1, null);
            DynamicConfigHistoryEntity rollback = historyLog.get(historyLog.size() - 1);
            assertThat(rollback.getOperationType()).isEqualTo("ROLLBACK");
            assertThat(rollback.getNewValue()).isEqualTo("sk-live-123456");

            ConfigItemView after = service.getItem("business.test.webhookSecret");
            assertThat(after.value()).isEqualTo("******");
        });
    }

    // ==================== 管理端查询 ====================

    @Test
    @DisplayName("列表合并注册表与 DB 覆盖：无覆盖项展示静态默认")
    void listMergesRegistryAndDb() {
        currentRow.set(row("rag.retrieval.topK", "8", 1));
        UserContextHolder.runWith(admin(), () -> {
            List<ConfigItemView> items = service.listItems("rag.retrieval");
            assertThat(items).extracting(ConfigItemView::key)
                    .contains("rag.retrieval.topK", "rag.retrieval.minScore", "rag.retrieval.strictKb");
            ConfigItemView topK = items.stream().filter(v -> v.key().equals("rag.retrieval.topK")).findFirst().orElseThrow();
            assertThat(topK.value()).isEqualTo("8");
            assertThat(topK.version()).isEqualTo(1);
            ConfigItemView strictKb = items.stream().filter(v -> v.key().equals("rag.retrieval.strictKb")).findFirst().orElseThrow();
            assertThat(strictKb.value()).isNull(); // 无覆盖 → 走默认
            assertThat(strictKb.defaultValue()).isEqualTo("false");
        });
    }

    private DynamicConfigHistoryEntity history(String key, int version, String op, String oldVal, String newVal) {
        DynamicConfigHistoryEntity h = new DynamicConfigHistoryEntity();
        h.setConfigId(1L);
        h.setConfigKey(key);
        h.setVersion(version);
        h.setValueType("INTEGER");
        h.setOperationType(op);
        h.setOldValue(oldVal);
        h.setNewValue(newVal);
        h.setOperatorRef("user:1");
        h.setCreateTime(LocalDateTime.now());
        return h;
    }

    /** 经缓存读取契约取当前生效值（模拟 B/C/D 消费方） */
    private Object readTopK() {
        return cache.typedValue("rag.retrieval.topK");
    }
}
