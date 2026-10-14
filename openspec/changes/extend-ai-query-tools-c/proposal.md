# extend-ai-query-tools-c — AI 查询工具面扩展（C 批 4 工具与评测同步）

## 背景与动机

- 截至 `extend-ai-draft-and-eval`（cd62301）与 `active-handoff.md`，AI 助手已具备 27 个工具（18 查询 + 9 草稿），涵盖订单、合同、商机、客户、跟进任务、业务活动、回款、发票、统计图表等，全部权限同锚。
- 在 2026-10-14 轮换接手拍板中，用户明确指示启动「AI 助手 C 批查询工具（扩展用户信息与组织查询：queryUser、queryDept 等）」。
- C 批候选在 `work/_paa-candidates-pool.md` §5 已有明确留档：
  1. `queryUser`：系统内部用户查询（与外部联系人 queryContact 区分，挂 SYSTEM_VIEW_USER，配合 @Privacy 脱敏）；
  2. `queryDept`：系统组织架构部门查询（与客户集团 queryCompanyGroup 区分，挂 SYSTEM_VIEW_USER）；
  3. `getCustomerCompanyDetail`：客户公司详情查询（补齐已有 getOrderDetail / getContractDetail / getOpportunityDetail 对称性，挂 CUSTOMER_VIEW_COMPANY）；
  4. `queryReport`：合同与商机统计报表查询（直通报表管理模块，挂 REPORT_VIEW_REPORT）。
- 本卡将这 4 个查询工具接入 AI 工具注册表，并同步扩展 AiEval 黄金评测集（34 → 40 例，含边界辨析）。

## 变更内容

### 1. 新增 4 个查询工具（全部挂既有权限常量，零权限语义新增）

| 工具名 | 数据面（对应 Controller / Service 实测） | 权限锚（既有常量） |
|--------|-----------------------------------------|-------------------|
| queryUser | 系统用户分页查询（UserController / UserService#findPage） | SYSTEM_VIEW_USER (601) |
| queryDept | 组织架构部门查询（SysDeptController / SysDeptService#listEnabled） | SYSTEM_VIEW_USER (601) |
| getCustomerCompanyDetail | 客户公司档案详情（CustomerCompanyController / CustomerCompanyService#getCompanyDetail） | CUSTOMER_VIEW_COMPANY (104) |
| queryReport | 合同/商机统计报表数据（ReportController / ReportService#getTotalSignContractNum / getTotalBusinessNum） | REPORT_VIEW_REPORT (501) |

- **权限同锚原则**：严格与页面端点权限一致，「页面看得到的 AI 才拿得到」，不引入任何新权限码。
- **隐私与安全**：`UserVO` 天然实现 `Privacy` 接口，手机与邮箱脱敏保护；详情与列表均经 `trimPageResult` 限制最大 10 条结果防刷屏。

### 2. 实现载体与结构

- `AiToolRegistry`：
  - 新增私有方法 `registerSystemTools(callbacks, user)` 编排系统用户与部门工具；
  - `getCustomerCompanyDetail` 编排入 `registerCustomerTools`；
  - `queryReport` 编排入 `registerAnalyticsTools`；
  - 严格遵守 PMD NCSS 与方法长度规约，单方法与类级 NCSS 达标。
- `AiQueryToolExecutors`：
  - 新增 `executeQueryUser`、`executeQueryDept`、`executeGetCustomerCompanyDetail`、`executeQueryReport`；
  - 纯调既有 service 层方法，参数校验完整，必填项缺失抛 `IllegalArgumentException` 返回修复提示。
- `AiEval` 黄金评测集：
  - `src/test/resources/ai-eval/cases.json` 追加 4 新工具用例与 2 个边界辨析用例（共 6 例，34 → 40 例）；
  - `AiEvalCaseSetTest` 追加新工具覆盖断言。
- 单测与断言：
  - `AiToolRegistryContextTest` 增加对应权限用户可见性断言；无权用户（11）与低权样本用户（13）计数保持不变；
  - `AiQueryToolExecutorsTest` 增加 4 执行器的纯 JVM mock 单测。

## 不做什么

- 不新增/不修改任何 Controller 端点与路由（`PermissionCoverageScanner` 注册表零改动）。
- 不触碰草稿工具域（AiDraftToolExecutors）与确认链（ActionTypeEnum / PendingActionServiceImpl）。
- 不修改数据库结构（零 Flyway 迁移脚本）。
- 不外发真实模型调用（纯 JVM 离线测试，DASHSCOPE_API_KEY 置空）。
- 禁碰 frontend/ 目录。

## 验收

1. 4 新工具按权限动态注入：无权限用户不可见，持有对应权限用户可见（单测断言覆盖）。
2. 每个新工具有完整的 mock service 行为单测（参数解析、必填校验、查空与裁剪）。
3. 黄金评测集 `cases.json` 扩容至 40 例，`AiEvalCaseSetTest` 校验覆盖新工具与无重复 ID。
4. `mvn -B -ntp test` 全绿，surefire 数量只增不减（基线 1280，revision=7450a2b）。
5. 四静态门禁（checkstyle / spotbugs / spotless / pmd）顺序全绿，双射守卫 exit 0。
6. `scripts/merge-gate.sh`（聚合门禁）退出码 0。
7. 新代码包含中文 Javadoc 标注「extend-ai-query-tools-c 任务 x.x」。
8. 复核档位属于 B 档（常规业务代码 + 测试），无迁移无外呼。

## 红线

- 零迁移（不碰 `db/migration`）、零权限语义新增（仅复用 601 / 104 / 501 常量）、零真跑外呼、零冻结契约触碰。
- 严禁未经授权执行 `git push` 或改写分支历史。
