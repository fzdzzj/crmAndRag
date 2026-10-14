# extend-ai-query-tools-c — 任务清单

> 提交粒度与任务组对齐：每任务组一笔提交，分支 `feature/extend-ai-query-tools-c` 自权威树 master 检出。
> 依据行格式：6 空格 + 实测证据（命令 + 关键数字/哈希）。未实际执行的步骤禁止打勾。

## 任务 1 · 注册表扩展（AiToolRegistry）

- [x] 1.1 `AiToolRegistry` 注册 4 个新查询工具：queryUser（SYSTEM_VIEW_USER）/ queryDept（SYSTEM_VIEW_USER）/ getCustomerCompanyDetail（CUSTOMER_VIEW_COMPANY）/ queryReport（REPORT_VIEW_REPORT）；description 与 inputSchema 对齐既有工具文案风格（含查空放宽引导 AMBIGUITY_GUIDANCE）
      依据：AiToolRegistry.java 实测追加 4 处注册——queryUser/queryDept 入 registerSystemTools（权限 SYSTEM_VIEW_USER 602）、getCustomerCompanyDetail 入 registerCustomerTools（CUSTOMER_VIEW_COMPANY 117）、queryReport 入 registerAnalyticsTools（REPORT_VIEW_REPORT 501，枚举实测值见 PermissionOperates.java L41/L223/L236）；四者 description 均含 AMBIGUITY_GUIDANCE 查空放宽引导
- [x] 1.2 编排结构治理：新增私有方法 `registerSystemTools` 注册 queryUser 与 queryDept，`getCustomerCompanyDetail` 放入 `registerCustomerTools`，`queryReport` 放入 `registerAnalyticsTools`，拆分后 NCSS 达标，`AiToolExecutorSplitTest` 全绿
      依据：mvn -B -ntp pmd:check 退出码 0（registerSystemTools 拆分后 AiToolRegistry 类 NCSS 达标；同轮 pmd 覆盖 AiQueryToolExecutors）；AiToolExecutorSplitTest 实测 Tests run: 2, Failures: 0, Errors: 0, Skipped: 0
- [x] 1.3 `AiToolRegistryContextTest` 补断言：持有对应权限的用户可见 4 新工具；null 用户仍 11 个、低权样本用户仍 13 个（新工具全挂权限，既有计数断言预期不变，若实测变化需在依据行说明原因）
      依据：mvn -B -ntp test -Dtest=AiToolRegistryContextTest 退出码 0，Tests run: 9, Failures: 0, Errors: 0, Skipped: 0；nullUser 计数断言仍 11、lowPrivilegeUser 计数断言仍 13（新工具全挂权限故既有计数不变），userWithBatchCQueryPermissions_seesPermittedTools 断言 queryUser/queryDept（602）、getCustomerCompanyDetail（117）、queryReport（501）均可见

## 任务 2 · 执行器实现与单测（AiQueryToolExecutors）

- [x] 2.1 `executeQueryUser`：调 `UserService.findPage`，解析 realName/phone/email/deptId/roleId/roleName/status/分页 参数，返回经 `trimPageResult` 裁剪的列表
      依据：AiQueryToolExecutors.executeQueryUser 调 UserService.findPage，单测 executeQueryUser_parsesArgumentsAndReturnsPageRecords 全断言 realName/phone/email/roleName/roleId/status/pageNum/pageSize；deptId 因既有 GetUserDTO 无该字段未暴露（超范围不改 pojo），roleId/status 以列表化传参
- [x] 2.2 `executeQueryDept`：调 `SysDeptService.listEnabled`（或按 includeDisabled 调 `listAll`），返回部门列表
      依据：executeQueryDept 按 includeDisabled 分派 SysDeptService.listEnabled / listAll，单测 executeQueryDept_defaultReturnsEnabledList 与 executeQueryDept_includeDisabled_returnsAllList 全绿
- [x] 2.3 `executeGetCustomerCompanyDetail`：companyId 必填校验，调 `CustomerCompanyService.getCompanyDetail`
      依据：executeGetCustomerCompanyDetail 调 CustomerCompanyService.getCompanyDetail，单测 executeGetCustomerCompanyDetail_validId_callsService 与 _missingId_throwsIllegalArgumentException（"companyId 不能为空"）全绿
- [x] 2.4 `executeQueryReport`：reportType 必填校验（CONTRACT / BUSINESS / OPPORTUNITY），调 `ReportService.getTotalSignContractNum` 或 `getTotalBusinessNum`，支持时间解析
      依据：executeQueryReport 按 reportType 分派 ReportService.getTotalSignContractNum（CONTRACT）/ getTotalBusinessNum（BUSINESS 与 OPPORTUNITY 别名，底层服务仅两方法）；单测 CONTRACT / BUSINESS / 缺失抛错 三例全绿
- [x] 2.5 新增执行器行为单测（纯 JVM mock service）：每工具至少覆盖正常返回 + 参数解析路径；分页裁剪与查空提示与既有工具模式一致
      依据：mvn -B -ntp test -Dtest=AiQueryToolExecutorsTest 退出码 0，Tests run: 21, Failures: 0, Errors: 0, Skipped: 0（原 12 例 + 新增 9 例）；执行细节拆至 AiExtendedQueryToolExecutors（PMD 类 NCSS 治理），AiQueryToolExecutors 保留薄委派，测试断言与计数不变

## 任务 3 · 黄金评测集扩展与断言（AiEval）

- [x] 3.1 `src/test/resources/ai-eval/cases.json` 追加 4 新工具覆盖用例（用户、部门、客户详情、报表）与 2 个边界辨析用例（例如客户列表 vs 详情辨析、系统用户 vs 外部联系人辨析），总用例数达到 40 例
      依据：cases.json 实测 ConvertFrom-Json 计数 40（34→40），追加 6 条：query-system-user-by-name / query-dept-list / customer-company-detail-by-id / query-report-contract / discrimination-customer-company-detail（客户列表 vs 详情）/ discrimination-system-user-vs-contact（系统用户 vs 外部联系人）；JSON 语法合法，id 唯一（AiEvalCaseLoader 加载即校验非空/唯一）
- [x] 3.2 `AiEvalCaseSetTest` 补断言：验证黄金用例集覆盖 4 新工具（queryUser/queryDept/getCustomerCompanyDetail/queryReport），用例总数 ≥ 40 且无重复 id
      依据：mvn -B -ntp test -Dtest=AiEvalCaseSetTest 退出码 0，Tests run: 1, Failures: 0, Errors: 0, Skipped: 0；断言 hasSizeGreaterThanOrEqualTo(40) + doesNotHaveDuplicates + contains(queryUser/queryDept/getCustomerCompanyDetail/queryReport) 全绿

## 任务 4 · 门禁与基线收尾

- [x] 4.1 `mvn -B -ntp test` 全绿，surefire 数量只增不减（基线 1280），在树净状态单次执行 `bash scripts/check-test-baseline.sh --update` 更新基线并单独 chore 提交
      依据：mvn -B -ntp test BUILD SUCCESS，Tests run: 1290, Failures: 0, Errors: 0, Skipped: 0（04-full-test.log，1280→1290 只增不减）；树净单次 bash scripts/check-test-baseline.sh --update 写入 Baseline 1280→1290（surefire.tests=1290, source-revision=b23d737），单独 chore 提交 49e8a03
- [x] 4.2 四静态顺序跑（checkstyle / spotbugs / spotless / pmd）全绿 + `pmd-baseline-check.sh` + `spotbugs-exclude-staleness-check.sh` 通过
      依据：四静态顺序（04-statics.log）checkstyle 0 Checkstyle violations / spotless 989 files clean（0 needs changes）/ pmd BUILD SUCCESS（violations=0）/ spotbugs BugInstance size 0；守卫 pmd-baseline-check.sh RESULT=PMD_BASELINE_OK（实测 0 == 登记 0 == pom 阈值 0），spotbugs-exclude-staleness-check.sh RESULT=BIJECTION_OK（12 条豁免与 12 条 High 一一对应）
- [x] 4.3 三守卫门禁 + `scripts/merge-gate.sh` 全绿（Docker 在线，failsafe 116 tests 与前端 179 unit tests pass）
      依据：bash scripts/merge-gate.sh --with-verify 退出码 0，九子门禁全 PASS（[unit][spotbugs][pmd][it][baseline][frontend-unit][hook][bijection][pmd-baseline]）；surefire 1290 / failsafe 116, Failures: 0, Skipped: 7 / 前端 Vitest 179 passed（17 files）；PermissionCoverageAuditIT（Tests run: 2）与 SchemaDriftAuditIT 均在 [it] 内 0 失败（04-merge-gate.log，Docker 29.6.2 在线）
- [x] 4.4 复核档位 B 档证据链留档：raw 输出落 `work/tmp/extend-ai-query-tools-c/`
      依据：raw 完整留档于观察树 work/tmp/extend-ai-query-tools-c/staging/：04-full-test.log（605 KB，mvn test 全量）、04-merge-gate.log（1.57 MB，merge-gate --with-verify 全量）、04-statics.log（四静态），另存改造源文件快照（AiExtendedQueryToolExecutors/AiQueryToolExecutors/AiToolRegistry/cases.json 等）与 tasks-group1/2/3/4.md
