# 规范：平台动态配置管理页（platform-config-frontend）

状态：新增（add-platform-config-frontend 提案配套）

## ADDED Requirements

### Requirement: 权限核验前置

前端 SHALL 仅在确认后端 `/platform/config` 端点具备权限防护（方法级 `@RequirePermission`，或权限矩阵 INTENTIONAL_OPEN 登记且服务层强制超管）后方可实现页面；SHALL NOT 在前端以任何形式绕过权限（如隐藏入口即视为安全控制）。

#### Scenario: 非超管访问

- **WHEN** 非 roleId=1 用户打开配置页
- **THEN** 列表接口返回 FORBIDDEN(96005)，页面显示"配置加载失败"错误提示，不渲染可操作数据

### Requirement: 契约同步与生成

配置端点类型 SHALL 来自 `pnpm gen:api` 生成的 `src/api/axios/`，SHALL NOT 手写请求类型副本。hooks SHALL 通过生成 SDK 函数访问后端。

#### Scenario: gen:api 成功

- **WHEN** 执行 `pnpm gen:api`
- **THEN** `sdk.gen.ts` 含 getPlatformConfigItems / postPlatformConfigItems / getPlatformConfigItemsByKey / getPlatformConfigItemsByKeyHistory / postPlatformConfigItemsByKeyRollback / deletePlatformConfigItemsByKey / postPlatformConfigCacheRefresh，且 type-check 通过

### Requirement: hooks 层封装

`src/hooks/usePlatformConfig.ts` SHALL 提供配置项列表（命名空间分组 + 关键字搜索）、详情、历史、更新、回滚、恢复默认、缓存刷新，基于 Vue Query；保存成功后 SHALL invalidate 列表查询。组件 SHALL NOT 直接调用 API。

#### Scenario: 保存后刷新

- **WHEN** useConfigUpdate mutation 成功
- **THEN** `platformConfigItems` queryKey 被 invalidate，表格显示新版本号

### Requirement: 配置管理页展示

页面 `/privilege/platform-config` SHALL 按五个命名空间（ai.prompt / ai.model / rag.retrieval / rag.intent / business）分组列出配置键，每行 SHALL 展示键名、类型、当前值、默认值、范围/枚举护栏与影响面描述；敏感键 SHALL 掩码展示并禁用编辑。

#### Scenario: 分组与搜索

- **WHEN** 用户输入关键字
- **THEN** 仅保留键名、影响面描述或命名空间匹配的条目，分组顺序不变

### Requirement: 按类型编辑与保存确认

编辑弹窗 SHALL 按 valueType 出控件：BOOLEAN→开关、INTEGER/LONG→整数输入（含 min/max）、DOUBLE→数值输入、STRING_LIST→多行文本序列化 JSON、STRING→枚举下拉或文本域；本地校验不通过时确认按钮禁用。保存前 SHALL 弹二次确认，回显「旧值 → 新值」与该键的影响面描述。

#### Scenario: 修改 topK

- **WHEN** 超管将 rag.retrieval.topK 从 8 改为 10 并提交
- **THEN** 确认弹窗显示 `旧值：8 → 新值：10` 与影响面说明，确认后 POST /platform/config/items，成功 toast 并刷新列表

#### Scenario: 越界值拒绝

- **WHEN** 输入超出 [min,max] 范围的整数
- **THEN** 弹窗内红字提示且无法进入确认步骤
