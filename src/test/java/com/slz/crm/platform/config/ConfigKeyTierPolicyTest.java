package com.slz.crm.platform.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.slz.crm.knowledge.retrieval.KbRetrievalStrategyWhitelist;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 动态配置键三档定级封闭表 census 防呆测试（add-dynamic-config-key-tier-acl 任务 1.3）。
 *
 * <p>三重锁：①注册表全集与三档封闭集构成完全分区——67 键既无表外键、也无跨档重复；②每键 tierOf 判定 与所在封闭集一致、三档计数恰为 OPERATIONAL 35 / COST
 * 27 / STRUCTURAL 5（2026-10-17 wire-built-unfinished-infra 摘除 ai.model.* 六死键、2026-10-20
 * fix-citation-threshold-config 补 3 个引用对齐阈值键、2026-10-21 add-kb-image-slice-retrieval 补 3
 * 个图片切片摄取键、multimodal-shared-space 补 1 个真视觉向量摄取键后的口径）；③P-ac per-KB 12 键白名单全集 ⊆ OPERATIONAL
 * 档（运营调参键才能进 per-KB 覆盖）。
 *
 * <p>注册表新增键未登记定级时默认落 OPERATIONAL——本 census 的分区/计数断言会先红，提示同步登记定级封闭 表并更新 docs/dynamic-config-keys.md
 * 的「权限档位」列（改档需 owner 拍板）。
 */
class ConfigKeyTierPolicyTest {

  private static final DynamicConfigKeyRegistry REGISTRY =
      new DynamicConfigKeyRegistry(new ObjectMapper());

  @Test
  @DisplayName("census：注册表 67 键与三档封闭集完全分区，计数恰 35/27/5")
  void registryPartitionsExactlyIntoTiers() {
    Collection<ConfigKeyDefinition> defs = REGISTRY.definitions();
    assertThat(defs).hasSize(67);

    Set<String> all = new LinkedHashSet<>();
    all.addAll(ConfigKeyTierPolicy.operationalKeys());
    all.addAll(ConfigKeyTierPolicy.costKeys());
    all.addAll(ConfigKeyTierPolicy.structuralKeys());
    assertThat(ConfigKeyTierPolicy.operationalKeys()).hasSize(35);
    assertThat(ConfigKeyTierPolicy.costKeys()).hasSize(27);
    assertThat(ConfigKeyTierPolicy.structuralKeys()).hasSize(5);
    assertThat(all).hasSize(67);

    Set<String> registryKeys = new LinkedHashSet<>();
    for (ConfigKeyDefinition def : defs) {
      registryKeys.add(def.key());
      switch (ConfigKeyTierPolicy.tierOf(def)) {
        case OPERATIONAL -> assertThat(ConfigKeyTierPolicy.operationalKeys()).contains(def.key());
        case COST -> assertThat(ConfigKeyTierPolicy.costKeys()).contains(def.key());
        case STRUCTURAL -> assertThat(ConfigKeyTierPolicy.structuralKeys()).contains(def.key());
      }
    }
    assertThat(registryKeys).containsExactlyInAnyOrderElementsOf(all);
  }

  @Test
  @DisplayName("census：P-ac per-KB 12 键白名单全集落 OPERATIONAL 档")
  void perKbWhitelistAllOperational() {
    assertThat(ConfigKeyTierPolicy.operationalKeys())
        .containsAll(KbRetrievalStrategyWhitelist.KEYS);
  }
}
