# 任务清单：archive-batch-3

## §0 铁律与留痕

- 只许动：`openspec/changes/` 下 7 个目标目录的路径（git mv）+ 本卡目录文件（proposal/tasks）。超范围 ＝ 偏差，如实留痕披露。
- `git mv` 保 rename；提交禁 `git commit -- <pathspec>` 姿态（pathspec 会拆 rename 为 delete+add）。
- 零内容改动：移动前后任何归档卡文件内容字节不变（`git diff --stat` 应零内容行、纯 rename 记录）。
- 零外呼（DASHSCOPE_API_KEY 置空）；bash 一律用 `D:\git\Git\bin\bash.exe`。
- 停步点：两组全完 + 门禁留证 ＝ 停步回报，不合并不 push（C 档：指导侧单遍核验 + 机械脚本出表替代复核 agent）。

## 任务组 1 · 归档移动

- [x] 1.1 `git mv` 七目录入 `openspec/changes/archive/`：aichat-prompt-template-integration、b2-suite-recalibration、add-prompt-template-nesting、add-prompt-template-slots、calibrate-review-intensity、archive-batch-2、prompt-template-enhance；`git status` 验证全部为 rename 记录（R 标记）
      补勾依据：`git mv` 一次完成七目录，`git diff --cached --name-status` 得 14 条记录全部 `R100`（纯 rename 零内容），`git diff --cached --stat` = `14 files changed, 0 insertions(+), 0 deletions(-)`，无 A/D 记录。可复跑：`git diff --cached --name-status master...feature/archive-batch-3 | grep '^R100'`，已提交哈希 `efdff2e`。archive 目录 tracked 计数 master=62 → HEAD=69（恰增 7，既有目录未动）。
- [x] 1.2 本卡 proposal/tasks 复制入分支为 tracked 正版（对齐先例；主树草稿由指导侧合并后让位）
      补勾依据：`Get-FileHash`(SHA256) 与主树草稿 proposal.md/tasks.md 逐一比对 `match=True`（字节一致），复制后 `git add` 提交为 tracked 正版，已提交哈希 `3936895`。可复跑：`git cat-file blob HEAD:openspec/changes/archive-batch-3/proposal.md | sha256sum` 与主树草稿对比。

## 任务组 2 · 门禁与回报

- [x] 2.1 `$env:DASHSCOPE_API_KEY=""; mvn -B -ntp test` 全绿且 surefire 数不动（零 Java 构造即证）；`bash scripts/check-test-baseline.sh` 只读（禁 --update）不红
      补勾依据：`mvn -B -ntp test`（DASHSCOPE_API_KEY=""）`BUILD SUCCESS`，raw 关键行 `[INFO] Tests run: 1173, Failures: 0, Errors: 0, Skipped: 0`（与 calibrate 卡记录全绿数一致 → 零 Java 构造）。`D:\git\Git\bin\bash.exe scripts/check-test-baseline.sh` 只读退出 0 不红，raw 关键行 `回归基线门禁通过（基线文件：.../scripts/test-baseline.txt）`、`surefire 测试：报告=189 ... Tests run=1173 ... 基线 reports>=189 tests>=1151`。可复跑：`$env:DASHSCOPE_API_KEY=""; mvn -B -ntp test 2>&1 | Select-String 'Tests run: 1173'`。
- [x] 2.2 `bash scripts/tests/review-mechanical-check.sh --branch feature/archive-batch-3 --master-tree <主树路径> --tasks openspec/changes/archive-batch-3/tasks.md` 出表贴停步回报（新政策首次 C 档核验出表）；tasks 全勾 + §0 留痕（各笔哈希、门禁 raw 关键行、rename 记录验证）；停步回报
      补勾依据：`D:\git\Git\bin\bash.exe scripts/tests/review-mechanical-check.sh --branch feature/archive-batch-3 --master-tree D:/code/crmAndRag --tasks openspec/changes/archive-batch-3/tasks.md` 恒 exit 0，四段出表：1/4 权威树在 feature/archive-batch-3 HEAD efdff2e 工作树干净、协作树 HEAD bf79e52；2/4 分支 vs master 16 文件含 2 A + 14 R100 全 rename；4/4 四基线文件全部 `[一致]` 无漂移。可复跑命令见本行，全文贴停步回报。
