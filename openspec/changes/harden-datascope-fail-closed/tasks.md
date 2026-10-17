# 任务清单 · harden-datascope-fail-closed（数据权限切面 Fail-Closed 治理）

> 评审档位：**A 档**  
> 留痕约定：所有完成项下一行必须带有 6 空格缩进的实测依据行（命令 + 结果摘要）。

---

## 任务组 1 · 切面核心加固与 Fail-Closed 阻断机制

- [x] 1.1 加固 `QueryWrapperAspect#addDataScopeCondition(JoinPoint, QueryWrapper<?>)`：当 `currentUser != null` 且 `isManagedTable(tableName)` 时，捕获到异常严禁吞咽，若为 `BaseException` 原样重抛，否则抛出 `new BaseException(ErrorCode.PERMISSION_DENIED, "数据权限过滤失败，拒绝查询")`。
      mvn -B -ntp test -Dtest=QueryWrapperAspectTest -> Tests run: 8, Failures: 0；shouldThrowPermissionDeniedWhenQueryWrapperDataScopeFails 注入 IllegalStateException("db down") 后断言 BaseException(12002, "数据权限过滤失败，拒绝查询")；shouldRethrowBaseExceptionAsIsWhenInjectionFails 断言 BaseException 原样重抛（assertSame 同一实例）
- [x] 1.2 加固 `QueryWrapperAspect#addDataScopeCondition(JoinPoint, LambdaQueryWrapper<?>)`：同样严格实现 Fail-Closed 阻断逻辑。
      同上定向测试 -> shouldThrowPermissionDeniedWhenLambdaQueryWrapperDataScopeFails 对 Lambda 重载注入失败断言 BaseException(12002)，8/8 全绿
- [x] 1.3 加固 `QueryWrapperAspect#aroundQueryWithoutWrapper`：当针对托管表的方法转发 `invoke` 失败时，严禁回退执行 `joinPoint.proceed()`，解包并抛出 `BaseException` 阻断。
      同上定向测试 -> shouldThrowPermissionDeniedWhenForwardingFails 断言转发 RuntimeException("boom") 抛 BaseException(12002, "数据权限方法转发失败: boom") 且 verify(joinPoint, never()).proceed()；shouldRethrowBaseExceptionWhenForwardTargetThrows 断言 ITE 解包后 BaseException 原样重抛且严禁回退
- [x] 1.4 更新 `@SuppressWarnings` 说明与注释，保持符合 PMD / SpotBugs 规范。
      mvn -B -ntp pmd:check spotbugs:check -> BUILD SUCCESS（PMD 7.9.0 violations=0；SpotBugs analysis done 无新增发现）；两处抑制注释已改为 fail-closed 口径（"通用捕获兜底转 12002 阻断"/"反射invoke异常多源，统一解包转 12002 阻断"）

---

## 任务组 2 · 系统后台与非托管表放行安全性保障

- [x] 2.1 确认 `currentUser == null`（系统定时任务/异步重试）场景放行行为安全无回归，添加明确审计日志。
      定向测试 shouldBypassWhenUserNullForSystemTask -> currentUser==null 时 proceed 返回 raw-result、verifyNoInteractions(dataScopeService)；三处无用户分支统一落 LOGGER.debug("系统/后台任务无当前用户，跳过数据权限注入")
- [x] 2.2 确认非托管表（`!isManagedTable(tableName)`）直接放行原有逻辑不受阻断影响。
      定向测试 shouldBypassForUnmanagedTable -> SystemConfigMapper（system_config 不在 MANAGED_TABLES）已登录用户下 proceed 返回 raw-result、verifyNoInteractions(dataScopeService)

---

## 3. 任务组 3 · 单元测试与反向阻断验证套件

- [x] 3.1 新增 `src/test/java/com/slz/crm/unit/server/aspect/QueryWrapperAspectTest.java` 单元测试类。
      实际落盘 8 用例（规定 6 + BaseException 原样重抛双向 2）；JDK 动态代理夹具复刻 MyBatis $Proxy→接口名取表名链路
- [x] 3.2 实现 `shouldThrowPermissionDeniedWhenQueryWrapperDataScopeFails` 用例：模拟注入失败，断言抛出 `BaseException(12002)`。
      mvn -B -ntp test -Dtest=QueryWrapperAspectTest -> PASS（8/8）
- [x] 3.3 实现 `shouldThrowPermissionDeniedWhenLambdaQueryWrapperDataScopeFails` 用例：模拟 Lambda 注入失败，断言抛出 `12002`。
      mvn -B -ntp test -Dtest=QueryWrapperAspectTest -> PASS（8/8）
- [x] 3.4 实现 `shouldThrowPermissionDeniedWhenForwardingFails` 用例：模拟重载转发异常，断言严禁回退原方法并抛出 `12002`。
      mvn -B -ntp test -Dtest=QueryWrapperAspectTest -> PASS（8/8）
- [x] 3.5 实现 `shouldAllowQueryWhenDataScopeSucceeds` 用例：正常路径放行并成功注入条件。
      mvn -B -ntp test -Dtest=QueryWrapperAspectTest -> PASS；verify(service).addDataScopeCondition(same(wrapper), same(user), eq("customer_company"))
- [x] 3.6 实现 `shouldBypassWhenUserNullForSystemTask` 用例：系统后台线程（`currentUser == null`）正常放行原方法。
      mvn -B -ntp test -Dtest=QueryWrapperAspectTest -> PASS（8/8）
- [x] 3.7 实现 `shouldBypassForUnmanagedTable` 用例：非托管表正常放行原方法。
      mvn -B -ntp test -Dtest=QueryWrapperAspectTest -> PASS（8/8）

---

## 任务组 4 · 全量门禁校验与基线收敛

- [x] 4.1 运行靶向单元测试（`mvn -B -ntp test -Dtest=QueryWrapperAspectTest`），确保所有用例 100% 通过。
      mvn -B -ntp test -Dtest=QueryWrapperAspectTest -> Tests run: 8, Failures: 0, Errors: 0, Skipped: 0, BUILD SUCCESS
- [x] 4.2 运行全量 `mvn -B -ntp clean verify`，确保 Surefire 用例数净增（预计 1620 → 1626+），Failsafe 全部通过。
      mvn -B -ntp clean verify（日志 work/_harden-datascope-verify.log）-> VERIFY_EXIT=0，12min10s；surefire 1628（+8，新增 +1 套件）、failsafe 118 全过 skipped=7
- [x] 4.3 运行 `bash scripts/check-test-baseline.sh --update` 单次写入干净基线（杜绝 `-dirty` 后缀）。
      "D:/git/Git/bin/bash.exe" scripts/check-test-baseline.sh --update -> 已写入，source-revision=e0a9c39（无 -dirty）；surefire.reports=262、surefire.tests=1628、failsafe.reports=31、failsafe.tests=118、failsafe.skipped=7
- [x] 4.4 运行 `bash scripts/merge-gate.sh`，确保 8/8 子门禁全绿，并恢复 `typed-router.d.ts` 噪声。
      "D:/git/Git/bin/bash.exe" scripts/merge-gate.sh（日志 work/_harden-datascope-mergegate.log）-> GATE_EXIT=0，unit/spotbugs/pmd/baseline/frontend-unit/hook/bijection/pmd-baseline 8/8 PASS；git checkout -- frontend/typed-router.d.ts 已恢复
- [x] 4.5 勾选任务清单并在各项下方附注 6 空格缩进实测证据，产出 `work/handoff-harden-datascope-fail-closed.md`。
      tasks.md 全项勾选并附实测依据行；work/handoff-harden-datascope-fail-closed.md 已产出并随 docs 笔提交至本 feature 分支
