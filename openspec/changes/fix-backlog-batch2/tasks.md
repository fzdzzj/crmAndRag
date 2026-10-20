# 任务清单 · fix-backlog-batch2

> 勾选纪律：每项完成后在勾选行下一行留 6 空格 + 「补勾依据（日期 实测命令与结论）」；未完成不勾。

## 1. ⑦ SQLExceptionHandler 收口
- [ ] 1.1 :48 越界守卫 + 死代码清理 + 分支重构（组合/单列/回落三路清晰）
- [ ] 1.2 键空间隔离：索引名 7 键迁独立 map（yml+@Value+全仓消费面 grep 留证）
- [ ] 1.3 SQL 裸列名路径走 getNameByIndex（文案改善拍板项，对照表入 handoff）
- [ ] 1.4 测试：裸索引名不越界、组合/单列/未知索引三态、裸列名文案、既有路径回归

## 2. ⑧ DTO null-id 校验
- [ ] 2.1 5 DTO id 挂 @NotNull（实读确认 import/分组语义）
- [ ] 2.2 测试：@Valid 拒绝 null id（400 路径）+ 既有关联维护测试回归（50002 用例语义随前置拦截调整，逐处列明）

## 3. ⑫ Reflector 阈值注入
- [ ] 3.1 经 CitationThresholdsResolver 注入（默认零漂移）；测试：注入生效 + 默认回归

## 4. 验证与留证
- [ ] 4.1 定向全绿 + 全量 mvn -B -ntp clean verify（Docker 实测）
- [ ] 4.2 树净单次 check-test-baseline.sh --update + 校验 EXIT=0 + merge-gate 10/10
- [ ] 4.3 handoff（四子项对照/消费面/行为变化论证/零漂移）+ tasks 勾选 + docs 笔；停步未 merge 未 push
