package com.slz.crm.common.enumeration;

/**
 * 权限模块归属的单一事实源（close-permission-module-gap）。
 *
 * <p>模块归属只按权限 ID 号段判定，声明顺序即 {@code GET /permission/list} 返回 Map 的键顺序。 判定口径固定两步：先 {@link
 * PermissionOperates#fromId(Long)} 命中枚举（未命中 ⇒ {@link #OTHER}）， 再取该枚举 ID 的十进制首位数字（1 ⇒ 客户、2 ⇒ 销售、…、9
 * ⇒ 知识库）。先查枚举再取首位， 避免未来 4 位新号段被误判成 1-4 段。
 *
 * <p>号段表与防漂移测试见 {@code docs/permission-module-catalog.md} 与 {@code
 * PermissionModuleResolverTest}；任何新增权限常量落不准号段，该测试即红。
 */
public enum PermissionModule {
  /** 客户管理模块权限（1xx / 11xx）。 */
  CUSTOMER("客户管理模块权限", 1),
  /** 销售管理模块权限（2xx / 20xx-22xx）。 */
  SALES("销售管理模块权限", 2),
  /** 财务管理模块权限（3xx / 30xx）。 */
  FINANCE("财务管理模块权限", 3),
  /** 联络任务模块权限（4xx / 40xx）。 */
  TASK("联络任务模块权限", 4),
  /** 统计报表模块权限（5xx）。 */
  REPORT("统计报表模块权限", 5),
  /** 权限管理模块权限（6xx）。 */
  PERMISSION("权限管理模块权限", 6),
  /** 隐私信息查看权限（7xx）。 */
  PRIVACY("隐私信息查看权限", 7),
  /** AI 模块权限（8xx）。 */
  AI("AI 模块权限", 8),
  /** 知识库管理模块权限（9xx）。 */
  KNOWLEDGE("知识库管理模块权限", 9),
  /** 其他模块权限（枚举中不存在的历史脏行兜底）。 */
  OTHER("其他模块权限", 0);

  private final String displayName;
  private final int segment;

  PermissionModule(String displayName, int segment) {
    this.displayName = displayName;
    this.segment = segment;
  }

  /**
   * 模块展示名（{@code /permission/list} 的 Map 键，对外逐字稳定）。
   *
   * @return 展示名
   */
  public String displayName() {
    return displayName;
  }

  /**
   * 按权限 ID 解析模块：不在 {@link PermissionOperates} 中的 ID 一律归「其他模块权限」。
   *
   * @param permissionId 权限 ID，允许 null
   * @return 模块枚举，恒非 null
   */
  public static PermissionModule fromPermissionId(Long permissionId) {
    PermissionModule result = OTHER;
    PermissionOperates operates = PermissionOperates.fromId(permissionId);
    if (operates != null) {
      result = fromOperates(operates);
    }
    return result;
  }

  /**
   * 按权限枚举解析模块：取枚举 ID 的十进制首位数字定段。
   *
   * @param operates 权限枚举，允许 null（⇒ 其他模块权限）
   * @return 模块枚举，恒非 null
   */
  public static PermissionModule fromOperates(PermissionOperates operates) {
    PermissionModule result = OTHER;
    if (operates != null) {
      result = fromSegment(leadingDigit(operates.getId()));
    }
    return result;
  }

  /** 按段位取模块；段位只在 1-9 内取值，其余（含 0）归「其他模块权限」。 */
  private static PermissionModule fromSegment(int segment) {
    PermissionModule result = OTHER;
    for (PermissionModule module : values()) {
      if (module.segment == segment) {
        result = module;
      }
    }
    return result;
  }

  /** 取十进制首位数字：1171 → 1、2041 → 2、900 → 9。 */
  private static int leadingDigit(Long permissionId) {
    long value = Math.abs(permissionId);
    while (value >= 10L) {
      value /= 10L;
    }
    return (int) value;
  }
}
