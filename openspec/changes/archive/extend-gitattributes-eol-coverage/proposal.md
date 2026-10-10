# 变更提案：.gitattributes 文本类型 eol 覆盖扩展（extend-gitattributes-eol-coverage）

## 1. 背景与问题定义
本机仓库级 `core.autocrlf=true`，`.gitattributes`（卡 P-h 产物）目前仅钉 `*.sh`、`.githooks/*`、`*.java` 为 `text eol=lf`。其余文本类型（md/txt/yml/yaml/xml/properties/sql/json）在 merge/checkout 检出时被转换为 CRLF，而对象库（index）恒为 LF。实测后果（卡 P-o 终审，2026-10-02）：
1. merge 后 `openspec/changes/archive/optimize-project-file-list-auth-reuse/tasks.md` 与 `scripts/test-baseline.txt` 工作树变 CRLF（`git ls-files --eol` 实测 `i/lf w/crlf`）；
2. 本地换行门禁 `agent-helper.sh check-line-endings lf` 红（`LINE_ENDING_VIOLATION: found CR`）；
3. 每张涉及非 Java 写集文件的卡在 merge 后都要人工判读 + sed + git add 处置，属可根治的重复摩擦；
4. 对象库健康（远端 CI Linux 检出 LF 无碍），纯本地环境摩擦。

## 2. 改进方案
在 `.gitattributes` 追加八类文本的 `text eol=lf` 规约：`*.md`、`*.txt`、`*.yml`、`*.yaml`、`*.xml`、`*.properties`、`*.sql`、`*.json`，并将 `.gitattributes` 自身钉 LF。eol 规约优先于 `core.autocrlf`，此后上述类型检出恒 LF，摩擦根除。

## 3. 安全等价论证
- 对象库已全部为 LF（i/lf），扩展 eol 规约不改变任何已提交内容——以 `git add --renormalize .` 后 `git status` 零变更（无 M 条目）为铁证；
- 远端 CI（Linux）检出行为不变（本来就是 LF）；
- 不触碰任何 Java/前端/SQL 迁移脚本的内容，仅改检出行为。

## 4. 约束边界与非目标
- 写集仅 2 个 tracked 文件：`.gitattributes` + 本 `tasks.md`（提案与 spec-delta 为未跟踪物料）；
- 零 Java 改动、零依赖、零 DDL、零前端触碰、测试基线数量不变（928）；
- 非目标：前端 ts/vue/js/css/html 等类型（如有独立摩擦另行立卡）；二进制类型标注；历史 CRLF 修复。

## 5. 验证与验收标准
1. 红对照（改前）：删除 `scripts/test-baseline.txt` 工作树副本后 `git checkout -- scripts/test-baseline.txt` 重检出 → `check-line-endings` 报 CR 红；
2. 绿对照（改后）：同一操作重检出为 LF，`check-line-endings` 绿；
3. `git check-attr eol` 对八类样本全部 `lf`；
4. `git add --renormalize .` 零变更（安全等价铁证）；
5. `check-dirty` CLEAN、基线脚本通过（928 不变）、136 条门禁自测全绿、四静态门禁 0 违规。
