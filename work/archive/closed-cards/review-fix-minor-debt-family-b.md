# B 档轻量复核 · fix-minor-debt-family 返场补丁

- 复核对象：`feature/fix-minor-debt-family`，tip `ae12b29`，基点 master `370c7d2`
- 返场增量：`805602a..ae12b29`（3 笔：`0d07514` 收紧门禁+映射 4 处 / `a8d136f` 台账更新 / `ae12b29` 文档）
- **结论：🟢 GO**（无阻断项；2 处非阻断观察 + 1 处提示词清单偏差，见 §4）
- 只核不改；未执行 merge / push（master 仍 `370c7d2`）

## 1. git 范围

`git diff 805602a..HEAD --stat`（实测 6 文件，+263/−71）：

| 文件 | 变化 |
|---|---|
| `src/main/.../server/service/impl/BusinessActivityRelationSupport.java` | 4 处构造改枚举双参 |
| `src/test/.../common/exiception/BaseExceptionConvergenceTest.java` | 收紧正则 + 2 例负向自证 |
| `src/test/.../server/service/impl/BusinessActivityRelationSupportTest.java` | 新增（4 例） |
| `work/handoff-fix-minor-debt-family.md` | 文档 |
| `openspec/changes/fix-minor-debt-family/tasks.md` | 勾选/依据 |
| `scripts/test-baseline.txt` | 台账 4 行（提示词清单未列，见 §4-①） |

- 指定路径零 diff 实测为空：`git diff master..HEAD --stat -- src/main/resources/db/migration pom.xml frontend/typed-router.d.ts frontend/src/utils/error-code-map.ts` → 无输出。
- 扩展到 `api/ *.sql src/main/resources/db/ frontend/ scripts/ pom.xml`：仅 `scripts/test-baseline.txt`（8 行内），api/sql/db/frontend/pom 全零。
- 树净：`git status --porcelain` 空；`git rev-parse --short HEAD`=`ae12b29`；master=`370c7d2` 未动。

## 2. 代码抽查

**① ConvergenceTest 严格枚举全匹配（:37-38、:138-140）**
`Pattern.compile("ErrorCode\\.[A-Z0-9_]+(\\s*,\\s*.+)?")` + `matcher(args).matches()` 全匹配：`ErrorCode.X` 与 `ErrorCode.X, 任意文案` 才放行。负向自证 `rejectsLegacySingleStringArgsConstructorForms`（:119-135）实测含全部要求样本：空参 `""`（:121）、`ErrorCode.PARAM_EMPTY.getMessage()`（:122）、`.getMessage().formatted(...)`（:125）、字符串字面量（:128）、`MessageConstant` 常量（:129）、`String.format(...getMessage()...)`（:132）；正向自证（:108-116）含裸常量/双参/枚举+formatted 三样本。扫描下限断言 `totalSites > 300`（:72）存在，防遍历失效假绿。

**② 4 处映射（BusinessActivityRelationSupport）**
实测行号 `:48/:93/:138/:169`（提示词 `:92/:135` 因双参换行后偏移 +1/+3，非缺陷），均为
`new BaseException(ErrorCode.PARAM_FORMAT_ERROR, MessageConstant.BUSINESS_ACTIVITY_{USER_DUPLICATE,CONTACT_ALREADY_EXIST})`，原文案常量整串保留（未截断/未改写）。`ErrorCode.java:153` = `PARAM_FORMAT_ERROR(50002, ...)`。新增 Test 4 例走真实 service 方法（`applyActivityUserRelations`/`applyActivityContactRelations`/`addContactRelations`/`addUserRelations`），断言 `getCode()==50002` 且 `hasMessage(MessageConstant.*)`，同时覆盖 `count==1` 与 `count>0` 两分支。

**③ 白名单/豁免**
旧版 `PENDING_DECISION_FRAGMENTS` 白名单与 `startsWith("ErrorCode.")` 宽松判定已整块删除（`git show 805602a:...` 对照）；`grep -i "whitelist|白名单|EXEMPT" src/test` 零命中，门禁零豁免。

**④ 映射表与文档**
handoff:61 为 `| 24 | PaymentRecordServiceImpl.java:70 | 合同不存在 | CONTRACT_NOT_EXISTS | 31001 | ...`，源码 `:70` 实测同为 `CONTRACT_NOT_EXISTS` +「合同不存在」，与表一致；旧 #24（InvoiceInfoServiceImpl:72 重列、映射列全 `—`）已删，无占位残留。`CustomerPoolServiceIT` 仅以「全仓不存在、原稿不实引用已删、登记待办池」出现（handoff:191/311），无虚引。口径统一 95/95。

## 3. 定向复跑

- `mvn -B -ntp -o test "-Dtest=BaseExceptionConvergenceTest,BusinessActivityRelationSupportTest"` → `Tests run: 10, Failures: 0, Errors: 0, Skipped: 0`、**BUILD SUCCESS**（6+4）；两份 surefire txt 实测 6/6、4/4 全绿。pwsh 侧 `[exit code: 1]` 系 JVM stderr 触发的 PowerShell NativeCommandError（stdout 明确 BUILD SUCCESS），非 maven 失败。
- `bash scripts/check-test-baseline.sh`（Git Bash，定向跑前/后各一次）→ **EXIT=0**：surefire 275/1720/0/0/0、failsafe 31/118/0/0/7，与台账一致；台账 `source-revision=0d07514`、`measured-at=2026-10-19T05:07:11Z`。
- 全量 clean verify 按 B 档约定未复跑（留给 M1）。

## 4. 非阻断观察

① **提示词清单偏差（非缺陷）**：返场实为 6 文件，多出 `scripts/test-baseline.txt`。该文件是 `--update` 从真实运行写入的回归台账（275/1720、31/118/7、source-revision=0d07514，与本次实测一致），非门禁逻辑；收窄前提「未碰 scripts」宜改述为「未碰 scripts 逻辑，仅台账 4 行」。台账更新是 1720 计数成立的必要条件，方向正确。
② 门禁扫描用 `indexOf("new BaseException(")`：`new BaseException (x)`（括号前有空格，Java 合法）与 `new com.slz...BaseException(` 形态不会被计数（前者只影响 totalSites 下限）。当前 src/main 实测无此形态，仅作后续加固提示。
③ `mvn` 复跑后 `target/` 内两份报告被重写，其余 273 份沿用上一轮全量运行；基线门禁两次均 EXIT=0，无计数漂移。
