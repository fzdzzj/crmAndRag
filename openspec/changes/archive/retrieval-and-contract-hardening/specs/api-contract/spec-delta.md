# spec-delta · api-contract（retrieval-and-contract-hardening G3）

> 变更对象：`frontend/openapi.yaml`（由 springdoc 导出的契约，**生成物**）。本 delta **只新增 `x-` 扩展**，既有 paths / operations /
> schemas / 字段形状**零改动**；`@RequirePermission` 注解、`PermissionsInterceptor` 拦截器、`OpenEndpointRegistry` / `PermissionCoverageScanner`
> 的**权限语义零变更**。
>
> 落地方式：`src/main/java/com/slz/crm/server/config/OpenApiPermissionCustomizer.java`（新增，`OperationCustomizer`）+
> `OpenApiConfig#openApiPermissionCustomizer()` 注册 bean。`src/main` 精确 2 文件。

## 1. 契约增量（格式冻结，合入即不可改形状）

```yaml
x-required-permission: { code: 608, name: PLATFORM_DYNAMIC_CONFIG_MANAGE }   # 有方法级 @RequirePermission
x-permission-status: OPEN                                                    # 无方法级注解（= 有意开放）
```

| 约定 | 内容 |
| --- | --- |
| **互斥** | 同一 operation 不得同时出现两者；也不得两者都缺（P1：0 例外） |
| `code` | **数字**，取 `PermissionOperates` 枚举的 `Long` 主键原样（枚举由 Lombok `@Getter` 暴露为 `getId()`；契约字段名固定 `code`） |
| `name` | 枚举常量名，全大写 |
| **扩展性** | 后续只许**新增** `x-` 键；**不得**改 (a)`x-required-permission` / (b)`x-permission-status` 的形状与取值口径 |
| `x-permission-status` 取值 | 唯一合法值 `OPEN` |
| **不表达** | 不表达"哪个角色有该权限"、不表达 COST 档位、不表达灰度白名单、不表达数据范围（`DataScopeLevel`） |
| **为什么不塞 `security`** | `securitySchemes` 表达**认证**（JWT），本系统权限是**方法级业务鉴权**，混用会误导 SDK 生成器 |

## 2. 稳定性承诺

1. **形状冻结**：两条扩展的键名、嵌套字段名（`code` / `name`）、取值类型（数字 / 全大写枚举名 / 字面量 `OPEN`）一旦合入不得更改；
   需要更强的表达能力时只允许**新增**顶层 `x-` 键。
2. **生成化**：扩展值只能由注解/登记事实**生成**，禁止手写进 `frontend/openapi.yaml`；覆盖入口唯一 =
   `bash scripts/check-openapi-consistency.sh --update`。
3. **零语义变更**：本 delta 不改任何鉴权行为——运行时判权仍只看方法级注解存在与否 + 角色权限数据；
   契约里的 `x-` 是**事实的投影**，不是新的判权来源。
4. **前端零破坏**：`pnpm gen:api` 生成物不因 `x-` 扩展而变化（生成器只吃 paths/schemas）。

## 3. 验收判据（P1–P4）与归一化声明

| 编号 | 判据 | 门槛 |
| --- | --- | --- |
| **P1** | 每个 operation 恰有 (a)(b) 之一 | 0 例外 |
| **P2** | 契约反推的 `SECURED(path\|METHOD)→code` / `OPEN 集合` 与 `PermissionCoverageScanner`+`OpenEndpointRegistry` **集合相等** | 任一处差异即红 |
| **P3** | `pnpm gen:api` 生成物 diff 为空或仅注释级；`lint:check`/`type-check:check`/`test`/`build` 全绿 | 任一红即停步 |
| **P4** | `src/main` 改动面 = `OpenApiConfig.java`（改）+ 1 个新 customizer 类 | 精确 2 文件 |

**归一化声明**（本 delta 是唯一登记处）：

1. **允许且唯一常规归一化**：**去尾部 `/`**（Spring 路由与 springdoc 对根路径写法差异）。
2. **额外归一化（本卡新增登记）**：扫描器对"方法级 mapping 未声明方法"的端点给出 `ANY`，而 springdoc 会把这类 operation 展开成
   **7 个具体方法**（GET/PUT/POST/DELETE/PATCH/HEAD/OPTIONS，TRACE 不导出）。P2 比对时把 `ANY` 按该**常量集**展开
   （常量写死在测试里，**不读契约**，两条路径仍独立）。实测受影响端点：`/permission/list`。
3. **禁止**：大小写归一化、路径参数名改写、前缀裁剪、code 补零/字符串化——出现即视为契约与源码不一致，如实变红。

## 4. 实测（本卡，`bash scripts/check-openapi-consistency.sh` 与 `OpenApiPermissionContractParityTest`）

| 指标 | 值 | 出处 |
| --- | --- | --- |
| operation 总数 | 279 | `OpenApiPermissionContractParityTest` 的 `[G3][P1]` 行 |
| SECURED | 259 | 同上 |
| OPEN | 20 | 同上 |
| 契约行数 | 11028 → 11825（+797 行，全是 `x-` 扩展） | `wc -l frontend/openapi.yaml` / `git diff --stat master` |
| `gen:api` 生成物 | **零变化** | `git status --porcelain frontend/` 只列出 `openapi.yaml` |
