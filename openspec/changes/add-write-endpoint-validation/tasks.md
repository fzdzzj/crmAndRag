# 任务清单 · 写端点参数校验收口 (add-write-endpoint-validation)

<!-- id: add-write-endpoint-validation -->
<!-- verify: bash scripts/merge-gate.sh -->

## 任务组 1 · 全局校验收口

- [x] 1.1 盘点全部写端点（POST/PUT/DELETE + @RequestBody），产出清单（controller/端点/DTO），与 openapi.yaml required 定义逐一对表——handoff 附清单
      实测 28/36 controller 含写端点共 86 条；双扫描互证（awk/python 两版状态机，逐 controller 双射对账一致）；GET 端点零 @RequestBody 无边界遗漏。
      契约对表 85/86 命中验证（唯一 MISS 为 AiChat value= 多属性 mapping 路径拼接差异，该端点已在位不动）；字段级 required 全仓仅 AI 聊天 2 处（message、resume.generationId）且注解已在位。
      依据：`work/handoff-add-write-endpoint-validation.md` §2.1/§2.2/§3（86 端点 × 断言形态清单）。
- [x] 1.2 controller 参数补 @Valid；DTO 按 required 定义补注解（未标 required 零约束）；逐 controller 反向单测（400 + PARAM_FORMAT_ERROR 断言）
      85 处 @Valid 补挂（86/86 覆盖，AiChat 1 处已在位）；契约 required 已在位 → DTO 零新增注解（未标 required 零约束口径）。
      反向单测基类 + 28 类 86 实例全绿；断言口径 = HTTP 200 + body code 50002 + 全局 handler 接管（与既有先例 AiChatControllerValidationTest 一致；HTTP 恒 200 为 J-4 现状契约，"400"落地为 body 层 400 通道）。
      依据：handoff §2.3/§2.4；spotless:apply 后 mvn compile 通过；写集 56 文件 < 80 体量闸未触发。

## 任务组 2 · 门禁与物料

- [x] 2.1 `mvn -B -ntp test` 基线只增（实测为准）→ `--update` 树净单次；merge-gate 8/8（Docker 在线 9/9）；typed-router 零 diff；产出 `work/handoff-add-write-endpoint-validation.md`（真实披露：写集清单、契约对表口径、体量实测）
      mvn test 全量 1570/0/0 绿；基线 surefire 1484→1574（+86 恰为本卡新增用例）已 --update 入库（16480e0）。
      merge-gate --with-verify（Docker 29.6.2 实测在线）exit 0 全绿：vitest 197/197、SpotBugs 双射 OK、PMD 基线 OK；typed-router 再生 17 行归因 master 既有产物漂移（salesLeadList），已还原零提交。
      依据：handoff §4（真实披露段含日志截断取证缺陷与 typed-router 归因）。
