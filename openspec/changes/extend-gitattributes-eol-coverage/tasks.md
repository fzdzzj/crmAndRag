# 任务分解与留痕：.gitattributes 文本类型 eol 覆盖扩展（extend-gitattributes-eol-coverage）

## 0. 执行记录
- 执行分支：`feature/extend-gitattributes-eol-coverage`
- 起点 commit：`master@fc529f4`
- 实施人员：子 agent
- 终审复验：复核子 agent（主 agent 只裁决）
- 受控写集实交：`.gitattributes` + 本 `tasks.md`（2 文件）
- 测试基线：数量不变（928/83），无需 `--update`
- 未跑项（默认门禁外）：failsafe IT（`mvn verify`，需 Docker）、真实模型外部调用、生产环境验证。
- 实测记录（2026-10-02，子 agent 自跑）：全量单测 `mvn -B -ntp test` = 928/0/0/0（BUILD SUCCESS，数量不变）；四静态门禁 `pmd:check spotbugs:check checkstyle:check spotless:check` = 0 违规（BUILD SUCCESS）；`check-test-baseline.sh`（未 `--update`）RC=0（surefire 报告 165/Tests run=928/Skipped=0，failsafe 报告 24/Tests run=83/Skipped=6，均满足台账下界）；门禁自测 101 + 35 = 136 条全绿；红对照 `LINE_ENDING_VIOLATION: scripts/test-baseline.txt:1: found CR` → 绿对照 `LINE_ENDINGS_OK`；八类样本 `git check-attr` 全 `eol: lf`；`git add --renormalize .` 零新增 M 条目（除写集内 `.gitattributes` 外 status 前后一致）。

## 1. 任务分解清单

### 阶段 1：分支检出与红对照
- [x] 1.1 从 `master@fc529f4` 检出特性分支 `feature/extend-gitattributes-eol-coverage`
- [x] 1.2 红对照（改前实测，贴输出）：删除 `scripts/test-baseline.txt` 工作树副本后 `git checkout -- scripts/test-baseline.txt` 重检出 → `& "D:\git\Git\bin\bash.exe" scripts/agent-helper.sh check-line-endings lf scripts/test-baseline.txt 2>&1 | Out-String` 报 `found CR` 红（本机 `core.autocrlf=true` 未被现有 .gitattributes 覆盖 txt）；重检出后该文件处于对象库等价内容，无需恢复动作。实测：重检出后 `git ls-files --eol` 显示 `i/lf w/crlf`，门禁输出 `LINE_ENDING_VIOLATION: scripts/test-baseline.txt:1: found CR (file must be pure LF)`，退出码 1

### 阶段 2：.gitattributes 扩展
- [x] 2.1 在 `.gitattributes` 既有三组规约后追加（保持注释风格，中文注释标注 `extend-gitattributes-eol-coverage 任务 2.1`）：
  ```
  # 文档与配置类文本统一纯 LF，消除 autocrlf 检出转换与本地换行门禁摩擦（卡 P-p）
  *.md text eol=lf
  *.txt text eol=lf
  *.yml text eol=lf
  *.yaml text eol=lf
  *.xml text eol=lf
  *.properties text eol=lf
  *.sql text eol=lf
  *.json text eol=lf
  .gitattributes text eol=lf
  ```
  实测：纯追加 10 行（1 注释 + 8 类 + 自钉）；既有三组规约原样保留，`GitAttributesContractGuardTest` 3 条全绿（surefire 全量 928 含此守卫）

### 阶段 3：绿对照与安全等价验证
- [x] 3.1 绿对照：再次删除并 `git checkout -- scripts/test-baseline.txt openspec/changes/optimize-project-file-list-auth-reuse/tasks.md` 重检出 → `git ls-files --eol -- <两文件>` 显示 `w/lf` → `check-line-endings lf` 绿。实测：两文件均 `i/lf w/lf attr/text eol=lf`，门禁输出 `LINE_ENDINGS_OK: scripts/test-baseline.txt`，退出码 0
- [x] 3.2 `git check-attr eol text -- <八类各取一个真实样本路径>` 全部 `eol: lf`。实测样本：`docs/migration-runbook.md` / `scripts/test-baseline.txt` / `.github/workflows/ci.yml` / `frontend/openapi.yaml` / `pom.xml` / `src/main/resources/application.properties`（全仓无 tracked `.properties` 文件，用代表性路径验证 `*.properties` 模式绑定，check-attr 按路径求值不要求文件存在）/ `src/main/resources/db/migration/V1__baseline.sql` / `frontend/package.json`，另加 `.gitattributes` 自身，九条路径全部 `eol: lf` + `text: set`
- [x] 3.3 安全等价铁证：`git add --renormalize .` → `git status --porcelain` 零 M 条目（对象库内容零变化），若出现任何 M 停步回报。实测：renormalize 前后 status 完全一致，唯一 M 为写集内已暂存 `.gitattributes`（+10 行），零新增 M 条目
- [x] 3.4 `git status` 全局 CLEAN（未跟踪物料不计）。实测：两笔提交后 `check-dirty` = CLEAN（见 4.4）

### 阶段 4：门禁与自测
- [x] 4.1 `mvn -B -ntp test` 全量 928/0/0/0（数量不变）。实测：`Tests run: 928, Failures: 0, Errors: 0, Skipped: 0`，BUILD SUCCESS
- [x] 4.2 四静态门禁 `mvn -B -ntp pmd:check spotbugs:check checkstyle:check spotless:check` 0 违规。实测：BUILD SUCCESS（退出码 0）
- [x] 4.3 `& "D:\git\Git\bin\bash.exe" scripts/check-test-baseline.sh` 通过（不 `--update`）。实测：RC=0，surefire 报告 165（默认口径）Tests run=928 Failures=0 Errors=0 Skipped=0，failsafe 报告 24（默认口径）Tests run=83 Skipped=6，回归基线门禁通过
- [x] 4.4 三守卫：`check-line-endings lf .gitattributes scripts/test-baseline.txt openspec/changes/extend-gitattributes-eol-coverage/tasks.md` 全 OK、`check-write-set fc529f4 .gitattributes openspec/changes/extend-gitattributes-eol-coverage/tasks.md` 命中、`check-dirty` CLEAN。实测：三守卫在两笔提交后执行（check-write-set 检查已提交改动、check-dirty 要求净树，均需提交后才有意义），三守卫全绿
- [x] 4.5 门禁自测：`scripts/tests/check-test-baseline-selftest.sh`（101）+ `scripts/tests/agent-helper-selftest.sh`（35）= 136 条全绿。实测：`SELFTEST PASSED: 101 assertions` + `SELFTEST PASSED: 35 assertions`，退出码均 0

### 阶段 5：提交、合并与停步
- [x] 5.1 补齐本文件 §0 实测数字并勾选，2 笔提交：`chore(repo): .gitattributes 扩八类文本 eol=lf 规约（卡 P-p 阶段2/3）`、`docs(openspec): 登记 P-p 任务留痕并完成 tasks 勾选（卡 P-p）`
- [x] 5.2 切回 `master` 执行真 `--no-ff` 合并，分支保留不删
- [x] 5.3 严格停步：绝对禁止 `git push`，等待复核子 agent 回报与 owner 授权
