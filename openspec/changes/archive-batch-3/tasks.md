# 任务清单：archive-batch-3

## §0 铁律与留痕

- 只许动：`openspec/changes/` 下 7 个目标目录的路径（git mv）+ 本卡目录文件（proposal/tasks）。超范围 ＝ 偏差，如实留痕披露。
- `git mv` 保 rename；提交禁 `git commit -- <pathspec>` 姿态（pathspec 会拆 rename 为 delete+add）。
- 零内容改动：移动前后任何归档卡文件内容字节不变（`git diff --stat` 应零内容行、纯 rename 记录）。
- 零外呼（DASHSCOPE_API_KEY 置空）；bash 一律用 `D:\git\Git\bin\bash.exe`。
- 停步点：两组全完 + 门禁留证 ＝ 停步回报，不合并不 push（C 档：指导侧单遍核验 + 机械脚本出表替代复核 agent）。

## 任务组 1 · 归档移动

- [ ] 1.1 `git mv` 七目录入 `openspec/changes/archive/`：aichat-prompt-template-integration、b2-suite-recalibration、add-prompt-template-nesting、add-prompt-template-slots、calibrate-review-intensity、archive-batch-2、prompt-template-enhance；`git status` 验证全部为 rename 记录（R 标记）
- [ ] 1.2 本卡 proposal/tasks 复制入分支为 tracked 正版（对齐先例；主树草稿由指导侧合并后让位）

## 任务组 2 · 门禁与回报

- [ ] 2.1 `$env:DASHSCOPE_API_KEY=""; mvn -B -ntp test` 全绿且 surefire 数不动（零 Java 构造即证）；`bash scripts/check-test-baseline.sh` 只读（禁 --update）不红
- [ ] 2.2 `bash scripts/tests/review-mechanical-check.sh --branch feature/archive-batch-3 --master-tree <主树路径> --tasks openspec/changes/archive-batch-3/tasks.md` 出表贴停步回报（新政策首次 C 档核验出表）；tasks 全勾 + §0 留痕（各笔哈希、门禁 raw 关键行、rename 记录验证）；停步回报
