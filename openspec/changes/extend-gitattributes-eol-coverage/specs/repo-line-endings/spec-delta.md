# 增量契约规范：仓库文本类型行尾规约覆盖（extend-gitattributes-eol-coverage）

## 1. 行为契约增量规范

### 契约 1：八类文本检出恒 LF（Deterministic LF Checkout）
- **GIVEN** 本机 `core.autocrlf=true` 的 Windows 检出环境，仓库中存在 `*.md`/`*.txt`/`*.yml`/`*.yaml`/`*.xml`/`*.properties`/`*.sql`/`*.json` 类型的已跟踪文件；
- **WHEN** 执行任意 merge/checkout 检出上述类型文件；
- **THEN** 工作树字节为纯 LF（`git ls-files --eol` 显示 `w/lf`），本地换行门禁 `check-line-endings lf` 对其恒绿。

### 契约 2：已提交内容零变化（Renormalize Invariant）
- **GIVEN** 上述扩展规约合入后；
- **WHEN** 执行 `git add --renormalize .`；
- **THEN** `git status` 零修改条目——对象库在扩展前后逐字节等价，远端 CI（Linux 检出）行为不变。

### 契约 3：既有 Java/sh 钮规不回退（No Regression on Existing Pins）
- **GIVEN** `*.java`、`*.sh`、`.githooks/*` 既有 `text eol=lf` 规约（卡 P-h 产物）；
- **WHEN** 本变更合入；
- **THEN** 三组既有规约原样保留且继续生效，八类新规约为纯追加。
