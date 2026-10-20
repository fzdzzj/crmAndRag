# 提案 · harden-test-backlog-batch1（待办池测试加固五点合卡，C 档）

## 为什么（待办池十二条之 ①②③④⑥，2026-10-20 看板 §3 权威口径）
五条纯测试加固债连续三卡滚存：防回归门禁存在格式化回滚盲区（`new BaseException (` 空格形态不被现正则命中）、release 置空无真库回环证据、TokenBudget volatile 并发用例缺区别力（移除 volatile 不红）、MessageConstant 构造 9 处仅断言文案未锁码、A-7 null id 分支无显式用例。

## 做什么（全部 src/test，零生产代码）
1. **① 空格形态加固**：`BaseExceptionConvergenceTest`（common/exiception）严格正则补齐 `new BaseException (`（括号前空格）形态 + 负向自证样本，防格式化回滚。
2. **② release 置空真库回环**：新增 Docker failsafe IT——公海 release 路径真库断言 owner 置空落库（软删回环证据），补 `CustomerPoolServiceIT` 虚引删除后缺口。
3. **③ TokenBudget 区别力**：`TokenBudgetPropertiesConcurrencyTest` 加固至「移除 volatile 即红」的区别力（跨线程可见性断言），并在测试注释注明区别力依据。
4. **④ MessageConstant 码断言**：9 处 `MessageConstant` 构造的 BaseException 断言补精确码（exec 实读定位 9 处所在测试，逐处补 `getCode()` 断言，码值以生产实读为准）。
5. **⑥ A-7 null id 用例**：`UserHandoverServiceImplTest` 补三类 null id（from/to/operator）跳过分支显式用例（batchGetUserNames/collectUserId 实读锚定）。

## 红线
**src/main 零 diff（本卡硬红线）** / 零迁移 / 零新依赖 / 零新错误码 / 不动前端 / 基线只增不减 / 既有测试断言不弱化 / 严禁 git push、merge、reset/rebase/amend、删分支。

## 档位
**C 档**（纯测试加固）：机械门禁退出码 0 + 主 Agent 审 diff，不派复核 Agent。

## 验收口径
定向（新增/受影响测试类）+ 全量 verify（Docker 实测，② 新 IT 落 failsafe 计数）+ 单次基线 --update + merge-gate 10/10；handoff 含五点前后对照与断言清单。停步：未 merge 未 push。
