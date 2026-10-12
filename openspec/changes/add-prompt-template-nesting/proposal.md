# proposal — add-prompt-template-nesting（提示词模板嵌套引用）

> 定位：纯渲染层能力增强——content 支持 `{{> code}}` 子模板引用，render 递归展开。
> 已拍板边界：段落级 section 元数据与检索槽位**另卡**（届时与 AiChat 检索装载同卡设计），本卡不做。

## Why

- V33 两种子骨架（agent-executor-brief / agent-reviewer-brief）为 owner 动机对象：执行/复核任务书骨架天然嵌套结构，公共段（纪律/环境/门禁约定）分散重复，改一处需同步多处。
- 现状 render 仅单层 `{{var}}` 替换（PromptTemplateRenderSupport#replacePlaceholders 逐 key replace），无内容组合能力。

## What Changes

- 新增 `{{> code}}` 引用语义：render 时递归解析启用态子模板并展开（**两遍法**：先展开骨架、后变量替换）。
- 新协作类 `PromptTemplateNestingSupport`（对齐 RenderSupport PMD 拆分先例）：引用抽取、递归展开、展开栈防环、深度上限。
- V36 种子示范：公共子模板落库 + 两骨架 content 引入引用 + history 快照 + FlywayMigrationIT 登记（迁移数 21→22）。
- 测试面：展开/异常/防环/变量并集/回归锁 ServiceTest + ControllerIT 端到端 + 种子渲染不回归。

## 拍板点（D1-D7）

| # | 点 | 建议 | 说明 |
|---|---|---|---|
| D1 | 语法与展开语义 | `{{> code}}` 递归展开、两遍法 | 引用取子模板**当前启用版本**；不引入 `{{> code@version}}` 指定版本语法（超范围） |
| D2 | 子模板异常语义 | 缺失→92008 ID_NOT_EXISTS、停用→92027 NOT_ENABLED、循环引用/超深度→PARAM_FORMAT_ERROR | 深度上限 8 常量兜底防炸栈；render 严格失败不降级——AiChat 消费侧由既有 WARN+静态回退链兜底（aichat 卡实测在案），坏模板显式暴露优于静默缺段 |
| D3 | 变量作用域 | 校验集 = 主模板与各层子模板 variables **并集**，required 按并集强制 | 调用方仍只提供一次变量集；备选「仅主模板定义校验」语义弱（子模板占位逃脱校验），不取 |
| D4 | 保存时校验 | create/update **不阻断**（前向引用合法：先建父后建子） | 对 content 中引用的 code 当前缺失时打 JUL WARN 留痕（非阻断，便于排查坏引用） |
| D5 | 种子示范 | V36 轻量落地：INSERT 公共子模板 `agent-common-discipline`（实读两骨架 content 比对抽取公共段为准）+ UPDATE 两骨架 content 嵌入 `{{> agent-common-discipline}}`（version+1、保留既有 {{var}} 占位）+ history 记 UPDATE 快照（V35 INSERT...SELECT 先例） | 直接兑现 owner 动机；备选「零种子改动纯测试示范」价值薄 |
| D6 | 权限/契约/DDL | 零新增权限、零契约变化、零新表 | 子模板不单独鉴权（嵌套是内容组合非访问面，主模板 render 的 808 已覆盖）；render 同入参同响应；复用 prompt_template 表 |
| D7 | PMD 协作类 | 展开逻辑独立 `PromptTemplateNestingSupport` | Service 保持薄，对齐 RenderSupport 先例；超阈即拆，§0 登记 |

## 不做（边界）

- 段落级 section 元数据、检索槽位、usage 记录表——另卡（与 AiChat 检索装载同卡）
- `{{> code@version}}` 指定版本引用、循环引用自动降级（跳过不拒绝）
- frontend 页面
- contracts-frozen.md 零触碰；既有迁移 V1-V35 零回改（两骨架 content 改造全部经 V36 新迁移）；SUITE_VERSION 不动；AiChat 动态段与检索组装 Java 追加逻辑逐字不动

## 影响面

- 写集预估：NestingSupport 新增 + ServiceImpl/Service 接口增量 + V36 迁移 + FlywayMigrationIT + ServiceTest/ControllerIT + 三件套 ≈ 8-9 tracked 文件
- 零外呼（DASHSCOPE_API_KEY 置空前缀）、零建表 DDL（V36 为种子 INSERT/UPDATE）、surefire 只增不减
- AiChat 运行时零代码改动；若后续人工改造 aichat-* 模板引入引用，展开失败自动回退静态（既有链在案）
