# Handoff · fix-review-mechanical-check-true（OBS-1 机械核对脚本修复）

> 档位：C 档（工程小卡，纯 scripts 修复，Java 侧零改动）
> 分支：`feature/fix-review-mechanical-check-true`（自 master@2a16989 检出）
> 完工停步等 Owner；未 merge、未 push。
> 证据存 `work/tmp/rmct-exec/`（normal.out / fail.out / run-normal.sh / run-fail.sh）。

## 一、吞错点定位与修复方式

审读 `scripts/tests/review-mechanical-check.sh`（109 行）后，Grep `|| true` 及同类吞错点共 5 处：

| 位置 | 原写法 | 性质 | 修复 |
| --- | --- | --- | --- |
| L38 `g()` 包装 | `git ... 2>/dev/null \|\| true` | **核心吞错**：所有 git 调用失败一律吞成 0 | `\|\| { err_mark; return 1; }`（跨子 shell 累计 + 透传退出码） |
| master-tree 两行（分支/HEAD） | `git ... \|\| true` | **吞错**：第二树不可读也伪装成功 | 改 `\|\| { err_mark; }` |
| 末尾 | `exit 0`（恒 0） | **吞错**：无论如何都清通过 | 按错误计数非 0 退出 |
| `diff_count` 行 `grep -c . \|\| true` | 保留 | **非吞错**：`grep -c` 对 0 行返回 1 属正常 0 匹配，保留 | 不动 |
| tasks.md `grep -c \|\| true` 两行 | 保留 | **非吞错**：0 匹配（未勾=0）正常返回 1，转成错误反而误报 | 不动 |

**关键判别**：`grep -c ... || true` 两处是**合法 0 匹配**处理（不是吞错），若改成记错会让「已全部勾选/无 diff」的正常报告误报失败——严格遵守边界「不改核对逻辑、不新增核对项」。

### 跨子 shell 传播方案（本卡的技术要点）
命令替换 `$(...)` 内 git 失败无法改父 shell 变量，故引入 `err_mark` 写临时错误文件（`mktemp` + EXIT trap 清理），末尾按文件行数判非 0。第一版用 shell 变量 `ERR` 子 shell 不传播（应失败场景实测仍误报 0），改为共享文件后正确。

### 漂移探测隔离（守住「不改核对逻辑」）
`cat-file -e` / `diff --quiet` 的非零返回是**单边缺失/漂移判定本身**（设计态），抽出独立 `gq()` 不计错、不吞错、原样透传——使 section 4 的基线漂移报告语义**完全不变**。边界三不动全部守住：不改核对逻辑、不新增核对项、不升格 CI 门禁（未碰 .github/workflows/ 与 scripts/merge-gate.sh）。

## 二、两向自测留证

| 场景 | 命令 | 退出码 | 证据 |
| --- | --- | --- | --- |
| 正常 | `bash scripts/tests/review-mechanical-check.sh` | **0** | `work/tmp/rmct-exec/normal.out`（末行 EXITCODE=0） |
| 应失败 | `bash scripts/tests/review-mechanical-check.sh --master-ref no-such-ref-zzz` | **2** | `work/tmp/rmct-exec/fail.out`（末行 EXITCODE=2），2 处非预期失败（section 2 两个 `g diff`），section 4 漂移项 `[单边缺失]` 属设计态不计错 |

构造应失败场景采用**最小侵入**：仅传不存在的 `--master-ref`，不改任何工作树文件、不污染他人文件，跑完即还原。

## 三、Java 侧零改动实证（主证）

```
git diff master --stat -- src/ frontend/ pom.xml   →  空（DIFFRC=0，零输出）
```
`mvn -B -ntp test` 实测 **BUILD SUCCESS，Tests run=1484，Failures=0，Errors=0，Skipped=0**（exit 0）。
`bash scripts/check-test-baseline.sh` **退出码 0（绿）**，对账 surefire tests=1484 与 `scripts/test-baseline.txt` 持平。
> 说明：卡片立项物料中的「1472」系立项时点旧基线，已按卡面要求以**当前基线 1484** 为准对账。

## 四、typed-router 状态

`git status --porcelain -- frontend/typed-router.d.ts` 为空 → **零 diff**（预期达成，未触碰他人改动文件）。

## 五、提交清单

| 提交 | 内容 | 明细 |
| --- | --- | --- |
| `cc9d1ff` | `fix(script)` | `scripts/tests/review-mechanical-check.sh`，1 file changed，+29/-9 |
| （本 handoff 所在提交） | `docs(ops)` | openspec 三件套 + 本 handoff + task-card，4 files changed，+112 |

分支 tip：`feature/fix-review-mechanical-check-true` = **`cc9d1ff`（代码笔，稳定）** + docs 笔（本 handoff 所在提交，即分支 tip）。合并前最终 tip 以 `git rev-parse --short feature/fix-review-mechanical-check-true` 实测为准（本 handoff 所在提交即该 tip）。

## 六、未 merge / 未 push 自证
- 未执行 `git merge`、未执行 `git push`。
- 分支仍为 `feature/fix-review-mechanical-check-true`，`HEAD` 独立于 `master`；完工树净仅剩白名单项（本卡 openspec 目录与 task-card 入库后移出，仅剩并联卡 `work/task-card-wire-customer-insight-panel.md` 一项白名单）。

## 七、披露项（不扩卡）——AGENTS.md 口径过期
卡面预告知属实：AGENTS.md「复核档位」段现写「机械核对表出 `scripts/tests/review-mechanical-check.sh`（非门禁，恒 exit 0）」，本卡修复后**「恒 exit 0」已过期**——脚本现按非预期失败返回非 0。
**本卡未改 AGENTS.md**（留 Owner 拍板）。建议同步文案（供 Owner 定夺）：
> 机械核对表出 `scripts/tests/review-mechanical-check.sh`（非门禁；设计态只报告，核对指令非预期失败以非 0 退出码如实反映）。

> 注：`docs/review-intensity-policy.md` §五 L40「该脚本恒 exit 0，只报告不判定」同口径同样过期；本卡同样只披露未改，是否随本卡或 Owner 另行统一处理由 Owner 定夺。