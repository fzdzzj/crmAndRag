# 任务清单 · OBS-1 机械核对脚本修复 (fix-review-mechanical-check-true)

<!-- id: fix-review-mechanical-check-true -->
<!-- verify: bash scripts/tests/review-mechanical-check.sh -->

## 任务组 1 · 脚本修复与自测

- [x] 1.1 审读 `scripts/tests/review-mechanical-check.sh`，移除 `|| true` 及同类吞错模式，失败真实传退出码；两向自测（应失败场景非 0 / 正常场景 0）留证；`mvn -B -ntp test` 抽跑确认 Java 侧零影响（基线 1472 持平）；产出 `work/handoff-fix-review-mechanical-check-true.md`（真实披露）
      补勾依据：脚本 `g()` 包装移除 `|| true`、master-tree 两行改 `err_mark`、漂移探测隔离为 `gq()` 不计错、末尾按错误数非 0 退出；应失败（`--master-ref no-such-ref-zzz`）实测退出码 2，正常实测退出码 0；`mvn -B -ntp test` 实测 Tests run=1484 Failures=0 Errors=0 Skipped=0（=当前基线，物料的「1472」为立项时旧基线）；`bash scripts/check-test-baseline.sh` 退出码 0；`git diff master --stat -- src/ frontend/ pom.xml` 为空、typed-router 零 diff。分支 `feature/fix-review-mechanical-check-true` tip=`cc9d1ff`，证据 `work/tmp/rmct-exec/`（normal.out / fail.out）。
