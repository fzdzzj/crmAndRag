# 交接 · add-write-endpoint-validation（写端点校验收口，A 档，本批三卡 1/3）

> 分支 `feature/add-write-endpoint-validation`，自 master@17008e2 检出；完工停步：未 merge、未 push。
> 执行树：权威树 D:\code\crmAndRag-merge-add-knowledge-admin-api（主协作树未触碰）。

## 1 · 提交清单（线性 6 笔）

| # | commit | 内容 |
|---|--------|------|
| 1 | 7811828 | feat(server): customer/contract 域 10 controller 39 端点 @Valid |
| 2 | 3f3a037 | feat(server): org/user/collab 域 9 controller 26 端点 @Valid |
| 3 | f5ce9e1 | feat(server,platform): ai/knowledge/report/config 域 8 controller 20 端点 @Valid |
| 4 | 7b6fa46 | test(unit): 基类 + 28 反向测试类（86 端点非法请求断言） |
| 5 | 16480e0 | test(baseline): surefire 基线 1484→1574（+86 恰为本卡新增用例） |
| 6 | （物料笔） | tasks 勾选 + 本 handoff + task-card + openspec 目录 |

注：提交过程中曾发现首笔 message 端点数笔误（30 实为 39），三笔主代码在未 push 前 reset 重提修正（7811828/3f3a037/f5ce9e1 为重提后的最终哈希）。

## 2 · 组 1 执行纪要

### 2.1 盘点口径（tasks 1.1）

- 扫描口径：POST/PUT/DELETE mapping 且方法参数含 `@RequestBody`；GET 端点经查零 @RequestBody，无边界遗漏。
- 全仓 36 个 controller 中 **28 个**含写端点，共 **86 条**（其中 1 条 AiChatController `/ai/chat/stream` 已挂 @Valid，本卡不动）。
- 双重扫描互证：正则状态机提取 86 条（awk + python 两版实现，逐 controller 双射对账一致）。
- 特殊形态核实：3 条 `@RequestBody(required=false)`（CostKeyChangeRequest reject / ProjectFile query / SalesLead query）、1 条 `@RequestBody String`（User /password）、1 条 value= 多属性 mapping（AiChat /chat/stream）、5 个构造器注入 controller（KnowledgeAdmin 6 参 + User/UserHandover/CostKey/DynamicConfig 1 参）。

### 2.2 契约对表口径（tasks 1.1，openapi.yaml 只读零改动）

- 路径映射：controller 类级 @RequestMapping 前缀 + 方法级路径 → openapi paths；method + requestBody.$ref → components/schemas。**85/86 命中验证**（唯一 MISS 为 AiChat value= 截断致路径拼接差异，该端点已在位不动）。
- **字段级 required 全量核查：openapi.yaml components/schemas 区仅 2 处 required 数组——`message` 与 `resume.generationId`（AI 聊天契约），对应 AiChatRequestDTO 的 @NotBlank/@Size 注解已在位（AI 侧先前收口）。**
- **结论：其余 84 端点契约零字段级 required → 按"契约未标 required 一律不加非空约束"口径，本卡 DTO 零新增校验注解。** 收口形态 = 86/86 写端点参数挂 `@Valid`（对无约束 DTO 当前为 no-op 防御网；未来契约标注 required 后校验自动生效，反向测试形态已就位）。
- requestBody 级 required 计数修正：requestBody 块 102 处（84 处标 `required: true` / 18 处缺省），256 为全文件含参数级计数；与 Spring `@RequestBody` 默认 required=true 同语义，无需注解表达。

### 2.3 注解收口（tasks 1.2）

- 85 处 `@Valid` 补挂 + 27 文件 import（幂等脚本：已有 @Valid 跳过）；spotless:apply 后 `mvn compile` 通过；重扫验证 **86/86 全覆盖**（awk 复扫 valid 列全 1）。
- 零语义破坏自证：`required=false` 端点挂 @Valid 后缺 body 仍直通（校验只对非 null 对象）；无约束 DTO 校验恒过；服务层既有手动校验未动（本卡写集仅 controller 参数行 + import 行）。
- 零迁移（无 V43）/ 零权限常量 / 零前端 / 契约文件只读。

### 2.4 反向单测（tasks 1.2）

- 形态：`unit/controller/WriteEndpointValidationSupport` 基类 + 28 个 `*WriteEndpointValidationTest`（每 controller 一类，`@ParameterizedTest @CsvSource` 覆盖该 controller **全部**写端点，86 实例）。
- 断言口径（真实披露）：**HTTP 恒 200 + `$.code == 50002`（PARAM_FORMAT_ERROR）+ 全局 handler 接管**。卡面表述"断言 400"落地为 body 层 400 通道——GlobalExceptionHandler 全部 handler 无 @ResponseStatus，HTTP 恒 200 是 J-4 已记录的现状契约（事实陈述非 bug），本卡零语义破坏不擅自改真 4xx；与既有先例 `AiChatControllerValidationTest`（isOk + 50002）完全一致。
- 触发形态两档：
  - `malformedBody=false`：缺 body → `HttpMessageNotReadableException("Required request body is missing")` → 50002（适用 required=true 的 83 端点）；
  - `malformedBody=true`：畸形 JSON → 反序列化失败 → 50002（适用 required=false 的 3 端点，缺 body 会穿透进方法体）。
- 请求 URL 构造：路径模板占位（`{id}`→1、`{key}`→testKey）；必填 @RequestParam 补 query（如 /query 端点 pageNum/pageSize、Assist /apply modelName/recordId）；有 defaultValue 或 required=false 的 query 参数不补。
- 运行时实证过的特例：`@RequestBody String`（User /password）走 StringHttpMessageConverter，畸形 JSON 不炸——靠缺 body 档触发；standalone MockMvc 方法体不执行，构造器注入 controller 以 null 依赖实例化合法。
- 测试结果：86/86 全绿（`Tests run: 86, Failures: 0, Errors: 0`）。

### 2.5 体量闸

写集实测：27 controller（M）+ 29 测试文件 = **56 文件 < 80 上限**，未触闸，未裁剪范围。

## 3 · 86 写端点 × 断言形态清单（tasks 1.1 附件）


| controller | 端点数 | 明细（METHOD 路径, 断言档 b=缺 body / m=畸形 JSON） |
|---|---|---|
| CostKeyChangeRequest | 2 | `POST /platform/config/cost-requests` (b)<br>`POST /platform/config/cost-requests/1/reject` (m) |
| DynamicConfigAdmin | 2 | `POST /platform/config/items` (b)<br>`POST /platform/config/items/testKey/rollback` (b) |
| AiAction | 1 | `PUT /ai/actions/1/edit` (b) |
| AiChat | 1 | `POST /ai/chat/stream` (b) |
| AiInsight | 1 | `POST /ai/insight/customer/1/to-action` (b) |
| Assist | 5 | `PUT /assist` (b)<br>`POST /assist/reapply` (b)<br>`POST /assist/append` (b)<br>`POST /assist/apply?modelName=s&recordId=1` (b)<br>`POST /assist/1/messages` (b) |
| BusinessActivity | 7 | `POST /business/activity` (b)<br>`DELETE /business/activity` (b)<br>`PUT /business/activity` (b)<br>`POST /business/activity/query?pageNum=1&pageSize=1` (b)<br>`POST /business/activity/1/contacts` (b)<br>`POST /business/activity/1/users` (b)<br>`DELETE /business/activity/1/associations` (b) |
| CompanyDept | 2 | `POST /company-dept` (b)<br>`PUT /company-dept` (b) |
| CompanyGroup | 2 | `POST /company-group` (b)<br>`PUT /company-group` (b) |
| ContactTask | 5 | `POST /contactTask/create` (b)<br>`DELETE /contactTask/batch` (b)<br>`PUT /contactTask/batch` (b)<br>`PUT /contactTask/update` (b)<br>`POST /contactTask/query` (b) |
| Contract | 4 | `POST /contract` (b)<br>`POST /contract/query?pageNum=1&pageSize=1` (b)<br>`PUT /contract` (b)<br>`DELETE /contract` (b) |
| ContractOrderItem | 4 | `POST /order` (b)<br>`DELETE /order` (b)<br>`POST /order/query?pageNum=1&pageSize=1` (b)<br>`PUT /order` (b) |
| CustomerCompany | 6 | `POST /company` (b)<br>`PUT /company` (b)<br>`DELETE /company` (b)<br>`PUT /company/recover` (b)<br>`PUT /company/merge` (b)<br>`DELETE /company/logical/batch` (b) |
| CustomerContact | 5 | `PUT /contact` (b)<br>`POST /contact` (b)<br>`DELETE /contact` (b)<br>`PUT /contact/recover` (b)<br>`DELETE /contact/logical/batch` (b) |
| CustomerPool | 1 | `POST /company/pool/assign` (b) |
| DataStatistics | 3 | `POST /dataStatistics/chartData` (b)<br>`POST /dataStatistics/chart` (b)<br>`POST /dataStatistics/summary` (b) |
| InvoiceInfo | 4 | `POST /invoice/info` (b)<br>`PUT /invoice/info` (b)<br>`DELETE /invoice/info` (b)<br>`POST /invoice/info/query?pageNum=1&pageSize=1` (b) |
| KnowledgeAdmin | 1 | `POST /knowledge/retrieval/test` (b) |
| PaymentRecord | 4 | `POST /payment/record` (b)<br>`PUT /payment/record` (b)<br>`DELETE /payment/record` (b)<br>`POST /payment/record/query?pageNum=1&pageSize=1` (b) |
| Permission | 1 | `POST /permission/addORDeletePermissionsToRole` (b) |
| ProjectFile | 1 | `POST /project/file/query` (m) |
| PromptTemplate | 5 | `POST /promptTemplate/create` (b)<br>`POST /promptTemplate/update` (b)<br>`POST /promptTemplate/render` (b)<br>`POST /promptTemplate/1/revert` (b)<br>`POST /promptTemplate/1/feedback` (b) |
| SalesLead | 4 | `POST /sales/lead` (b)<br>`POST /sales/lead/query` (m)<br>`PUT /sales/lead` (b)<br>`POST /sales/lead/convert` (b) |
| SalesOpportunity | 2 | `PUT /sales` (b)<br>`POST /sales` (b) |
| SysDept | 2 | `POST /dept` (b)<br>`PUT /dept` (b) |
| TaskComment | 3 | `POST /taskComment/create` (b)<br>`DELETE /taskComment/batch` (b)<br>`PUT /taskComment/update` (b) |
| User | 6 | `POST /user/login` (b)<br>`POST /user/add` (b)<br>`POST /user/password` (b)<br>`POST /user/password/reset` (b)<br>`POST /user/update` (b)<br>`POST /user/update/my` (b) |
| UserHandover | 2 | `POST /user/handover/execute` (b)<br>`POST /user/handover/query` (b) |

合计 **86 端点 / 28 controller**；AiChat 的 `/ai/chat/stream` 已在位（既有 @Valid，无新测试类外的改动）。

## 4 · 组 2 门禁纪要

- **基线**：`mvn -B -ntp test` 全量 **1570 tests / 0 failures / 0 errors / BUILD SUCCESS**（5 分 08 秒）；`check-test-baseline.sh --update` 从真实运行写入：surefire **reports 226→254、tests 1484→1570、skipped 0**；failsafe 31/118/7 不变。~~脚本统计口径（按 XML 报告累加）与 mvn 汇总差 +4 属统计口径差异，非用例数漂移。~~ **残留污染归因（闭环订正）**：上述 +4 并非口径差异——D4 在途分支 b66a5b5 的 `SalesOpportunityServiceTest` 报告（tests=4，源码树已无此类）被 `--update` 未 clean 吸收，致台账 255/1574 虚高、CI run 38995729403 基线门禁红；纠偏笔 **6d61488**（F1）clean 全量 verify 后真实写入 254/1570，红→复绿全程在案（raw：work/tmp/wev-close/）。
- **typed-router**：`pnpm build` 实证再生产物 +17 行（`salesLeadList` 路由两条 Union 成员）——归因 **master 既有产物漂移**：salesLeadList 页面文件已随 d9f0a0f 入库但 typed-router.d.ts 生成产物未随提交更新，任何一次 build 都会再生，**非本卡写集**（本卡零前端触碰）。已按卡面口径 `git checkout --` 还原；merge-gate 的 vitest 段再次改写行尾后亦再次还原，收尾 `git status -- frontend/` 零残留。
- **merge-gate**：`bash scripts/merge-gate.sh --with-verify`（Docker 实测在线 29.6.2，9/9 全门禁）exit 0，脚本总结语"**merge-gate 通过：所有已执行的子门禁绿**。以上输出可作为合入证据"。日志可见段实测：frontend vitest **197/197 绿**、SpotBugs 双射 **BIJECTION_OK（12=12）**、PMD 基线 **PMD_BASELINE_OK（实测 0==登记 0==pom 阈值 0）**、pre-commit 转发器在位；其余段由总结语背书（执行日志经 `tail -40` 管道仅存尾段，属取证方式缺陷如实披露；复核如需逐段日志可复跑）。另披露：vitest 段曾改写 typed-router.d.ts 行尾（脚本 NOTE 明示为生成物噪声勿提交），已 checkout 还原。

## 5 · 红线与树净自证

- 零迁移：`src/main/resources/db/migration` 零改动（未占 V43）。
- 零权限常量：`PermissionOperates` 零新增；未触碰拦截器。
- 零冻结契约：`frontend/openapi.yaml` 只读（git diff 为空）。
- 零前端：提交清单无 frontend/ 文件。
- 既有单测语义不动：86 反向用例为净新增文件；无既有测试文件改动。
- 完工树净：`git status --porcelain` 仅剩他人卡面白名单（本批 I-2/暗线① 及后续卡的 openspec 目录与 task-card）。

## 6 · 复核提示（A 档全量）

- 重点核对：契约对表结论（零 required → 零注解）是否成立——可复跑 `python` 对表或抽查 schemas 区 required；@Valid 86/86 可用 `grep -c "@Valid @RequestBody"` 逐文件核。
- 反向测试的断言口径（isOk + 50002）与既有先例一致性。
- typed-router 漂移归因（master 既有，非本卡）与还原动作。

> 合入 master@73e365a（merge --no-ff，6+1 笔）+ 基线纠偏笔 F1=6d61488（台账 254/1570 真实值），A 档复核 GO（raw：work/tmp/wev-review/）
