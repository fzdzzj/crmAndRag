# 任务清单 · fix-minor-debt-family

> 勾选纪律：每项完成后在勾选行下一行留 6 空格 + 「补勾依据（日期 实测命令与结论）」证据行；未完成不勾。

## 1. M-1 BaseException(String) 全仓收敛
- [ ] 1.1 盘点 src/main 全部 `new BaseException("` 单参字符串调用点，产出全量清单（file:line + 文案 + 方法）
- [ ] 1.2 制定逐点 ErrorCode 映射表（复用既有码；无合适码者单列待裁决），落 handoff
- [ ] 1.3 按映射表改枚举构造，行为面 code=0→真实码；src/test 同步改码断言
- [ ] 1.4 客户端可见端点错误码核对 `frontend/src/.../error-code-map.ts` 镜像，按需同步（§127 先例）
- [ ] 1.5 M-1 专项测试：每类映射至少 1 例断言 code 透传

## 2. 摄取 BLOCK 错误码收口 96004
- [ ] 2.1 确认 ContentRiskBlockedException 当前错误码与 96004 对应常量名
- [ ] 2.2 收口至 96004 并补断言（摄取 BLOCK 路径错误码 == 96004）

## 3. 大文件摄取扫描内存优化
- [ ] 3.1 readAllBytes 全量缓冲改为尺寸上限前置校验或分块评估（语义等价：BLOCK 仍落库前拒绝）
- [ ] 3.2 边界测试：超限尺寸被拒 / 正常尺寸不受影响

## 4. assign 目标用户存在性校验
- [ ] 4.1 配额行锁前补目标用户存在+在职校验（复用既有判存/状态错误码）
- [ ] 4.2 靶向测试：不存在/离职目标用户 assign 被拒，合法用户不受影响

## 5. release 置空显式落库
- [ ] 5.1 release 路径 setOwnerId(null) 等置空改显式 UpdateWrapper set null 真落库（casToPrivate 同型）
- [ ] 5.2 靶向测试：release 后 DB 行 ownerId/reason 字段真被清空

## 6. TokenBudget 无锁断言盘点处置
- [ ] 6.1 盘点确认无锁共享默认值点；确认则修（volatile/原子化）并补并发安全测试；过时则 handoff 记录后弃置

## 7. 基线与物料
- [ ] 7.1 定向测试全绿 + 全量 `mvn -B -ntp clean verify`（Docker 在线）
- [ ] 7.2 树净单次 `check-test-baseline.sh --update` + 校验模式 EXIT=0 + 基线笔
- [ ] 7.3 `merge-gate.sh` 10/10（typed-router 噪声 checkout 还原）
- [ ] 7.4 handoff + tasks 勾选 + 任务卡随 docs 笔提交；严格停步未 merge 未 push
