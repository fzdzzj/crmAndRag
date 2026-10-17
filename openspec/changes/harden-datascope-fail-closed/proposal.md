# 变更提案 · harden-datascope-fail-closed（数据权限切面 Fail-Closed 治理）

> 提案 ID：`harden-datascope-fail-closed`  
> 评审档位：**A 档**（涉及数据权限核心切面阻断语义变更，由独立复核 Agent 执行全量核查）  
> 状态：立项通过，待执行派发  

---

## 1. 痛点背景与问题定义

在系统的三级数据权限体系中，[`QueryWrapperAspect.java`](../../src/main/java/com/slz/crm/server/aspect/QueryWrapperAspect.java) 负责拦截所有 Mapper 层的查询操作，向 `QueryWrapper` / `LambdaQueryWrapper` 中自动注入部门、人员或标签级数据过滤条件。

然而，当前切面实现存在严重的 **Fail-Open 越权安全隐患**：
1. **注入异常被静默吞咽**：在 `addDataScopeCondition(JoinPoint, QueryWrapper<?>)`（L205-232）与 `addDataScopeCondition(JoinPoint, LambdaQueryWrapper<?>)`（L241-268）中，当调用 `dataScopeService.addDataScopeCondition` 发生任何异常（如数据库抖动、权限映射异常、空指针等）时，`catch (Exception e)` 仅打印 `LOGGER.error("添加数据权限条件失败", e)`，未向外重抛异常。导致针对 10 张核心托管表（如 `customer_company`, `contract`, `sales_opportunity` 等）的查询在没有任何条件过滤的情况下全量执行，普通员工瞬间可越权查看全公司全部敏感数据。
2. **方法转发失败回退无条件查询**：在 `aroundQueryWithoutWrapper(ProceedingJoinPoint)`（L140-145）中，当针对托管表自动转发至带 `QueryWrapper` 的方法失败时，`catch (Exception e)` 记录日志后回退调用 `joinPoint.proceed()`，直接放行无 Wrapper 的原始全量查询。
3. **源码注释放松自认**：L204 与 L240 带有 `@SuppressWarnings("PMD.AvoidCatchingGenericException") // 数据权限外呼服务多源，失败仅告警不阻断查询` 注释，表明此前将 Fail-Open 视为设计妥协，存在重大数据安全隐患。

---

## 2. 目标与方案设计

### 核心目标
将数据权限切面从“失败仅告警、查询裸奔”（Fail-Open）彻底转为“失败即阻断、拒绝查询”（Fail-Closed），杜绝数据泄露。

### 详细设计
1. **条件注入严格 Fail-Closed**：
   - 在 `addDataScopeCondition`（常规 Wrapper 与 LambdaWrapper 双重实现）中，当满足 `currentUser != null` 且 `ResourceTypeConstant.isManagedTable(tableName) == true` 时：
     - 若 `dataScopeService.addDataScopeCondition(...)` 抛出异常，捕获后严禁静默吞咽；
     - 若异常本身是 `BaseException` 则直接向外抛出；
     - 若为其他异常（如 `RuntimeException`），统一包装为 `new BaseException(ErrorCode.PERMISSION_DENIED, "数据权限过滤失败，拒绝查询")`（错误码 12002）抛出，由 Spring `@RestControllerAdvice` 统一转化为 12002 错误响应，安全阻断请求。
2. **重载转发严格 Fail-Closed**：
   - 在 `aroundQueryWithoutWrapper` 中，针对托管表转发带 Wrapper 方法时：
     - 若反射调用抛出 `InvocationTargetException`，提取 `getTargetException()`；若为 `BaseException` 原样重抛，否则包装为 `BaseException(ErrorCode.PERMISSION_DENIED, "数据权限方法转发失败: " + cause.getMessage())`；
     - 严禁在此路径回退执行 `joinPoint.proceed()`！
3. **系统后台与未登录线程安全隔离**：
   - 当 `currentUser == null` 时（定时调度任务如公海自动回收、流失预警、合同到期，以及后台异步摄取重试等）：
     - 保持现有白名单策略放行，记录明确的 `LOGGER.debug("系统/后台任务无当前用户，跳过数据权限注入")` 日志，确保后台作业不被误杀；
   - 非托管表（`!isManagedTable(tableName)`）：直接放行，不受影响。
4. **PMD / SpotBugs 规范合规**：
   - 调整 `@SuppressWarnings` 注释说明，保留对必要通用捕获的豁免，确保 `mvn -B -ntp pmd:check` 与 `spotbugs:check` 保持 0 违规。

---

## 3. 红线与边界约束

1. **零数据库迁移**：严禁新增或修改 `src/main/resources/db/migration/` 下的任何脚本。
2. **零外部依赖新增**：`pom.xml` 严禁新增任何 Maven 依赖。
3. **零新增错误码与权限常量**：严格复用既有 `ErrorCode.PERMISSION_DENIED`（12002）错误码，不新增常量。
4. **测试基线只增不减**：Surefire 测试基线自当前 `261` 报告 / `1620` 用例起算，必须保持只增不减（预计 +1 套件 / +6 用例）。
5. **保护后台定时任务**：确保所有既有 `@Scheduled` 定时任务在无用户会话下正常运行不报错。
