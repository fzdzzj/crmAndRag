# 增量契约规范：openspec/spec 双轨归属定夺与台账清账（resolve-dual-track-and-ledger）

### 契约 1：双轨权威归属（Dual-Track Authority）

- **GIVEN** spec/changes 与 openspec/changes 并存的现状；
- **WHEN** 本卡落地 owner 2026-10-10 拍板（「先拍板再清账」，推荐方案：双轨分工合法化）；
- **THEN** spec/changes = 平台总纲与冻结契约轨（add-crm-rag-fusion-platform 为长期活文档，契约改动走受控解冻；其余 13 目录历史规格资产只读保留、不迁移、不再新加）；openspec/changes = 逐卡变更唯一新卡轨（完工归 archive/）；检索链路辨权：改契约先查 spec/.../contracts-frozen.md，功能/修复改动经 openspec 卡走；此定论写入 AGENTS.md 与 openspec 规则文件后，后续任何卡/会话不得再以「归属未决」为由双目录无差别扫描。

### 契约 2：回补载体合法性（Retrofit Vehicle）

- **GIVEN** P-ab spec-delta 契约 5 预注册未回补的 27 格（P-ab 7 / P-ac 7 / P-ad 6 / P-ae 7）；
- **WHEN** 本卡对其逐格核验补勾；
- **THEN** 每格遵循 P-ab 契约 1 证据留痕格式（日期改 P-ag）与契约 6 断言语义冻结；本卡即契约 5 所指「下一张审计卡」，为合法回补载体。

### 契约 3：归档口径与引用同步（Archive Criterion）

- **GIVEN** openspec/changes/ 下已合入 master 且无悬空格的变更目录（授权门控格带 P-ab 日期化注记者视为无悬空）；
- **WHEN** 本卡归档整理；
- **THEN** 归档 = git mv（或 openspec CLI 既有惯例），非删除；全仓文字引用（git grep 旧路径）必须同步更新至归档后路径，归档完成后旧路径引用零命中；changes/ 仅保留在途卡。

### 契约 4：零代码面（Zero Code Surface）

- **GIVEN** 本卡纯文档/台账性质；
- **THEN** src/、frontend/、pom.xml、scripts/、db/migration 零 diff；surefire 1075 / failsafe 27 类 98 例 6 跳零变化，test-baseline.txt 不 --update；红测试豁免（P-z/P-aa/P-ab 先例）。

### 契约 5：停步与推送纪律（Stop-and-Push Discipline）

- **THEN** 执行终态 = 严格停步回报（原样粘贴实测输出，绝对禁止 git push）；推送、删除类动作归 owner 显式授权；预注册未勾格（6.4/7.1-7.5）按 P-ab 契约 5 由后续回补笔闭合。
