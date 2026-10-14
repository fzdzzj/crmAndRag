# proposal — harden-admin-uniqueness-and-hygiene

> 安全小修（终极管理员 roleId=1 唯一性双堵）+ 卫生批（3 处 TODO 清理）+ 归档批（6 目录）合卡（owner 2026-10-14 拍板「1 2 一起」）。

## 1. 背景（2026-10-14 master a14feed 立项实测）

功能审视（2026-10-13）定档的「安全 TODO 优先 + 卫生批」余项，与归档批（三卡候补）攒批合一卡。

**安全洞实测**：

1. `UserServiceImpl#addUser` L89-92：**被注释掉的校验块**——`// TODO 管理员不能添加管理员` + `if(userDTO.getRoleId() == 1){ throw new BaseException(MessageConstant.MASTER_IS_ONE); }`（整块注释禁用中）。即：当前任何能调 addUser 的用户都可以把新用户直接造成 roleId=1 终极管理员。
2. **`UserServiceImpl#updateUser` L234-236 同洞（本轮立项实测新发现）**：`if (userDTO.getRoleId() != null) { wrapper.set(UserEntity::getRoleId, userDTO.getRoleId()); }`——roleId 直接透传 set，存在把任意用户（含自己）改成 roleId=1 的提权路径；updateUser 还会清 password（「更新个人信息」口径），若为个人信息端点则普通登录用户即可自我提权。比 addUser 单点更严重。
3. `MessageConstant.MASTER_IS_ONE`（L112「终极管理员只能存在一位」）常量已存在、全仓零用点（仅被注释代码引用）——恢复校验即首用，零新增语义。
4. roleId 语义：0=冻结 / 1=终极管理员（超管，拦截器直通）/ 2=离职（PermissionsInterceptor 既有口径）；V26 种子对 0/1/2 不授业务权限。roleId=1 唯一性 = 系统内终极管理员只能存在一位（种子内置一位）。

**卫生批三处 TODO 实测**：

1. `MessageConstant` L24 `// TODO 记得删` 挂在 SERVER_ERROR（L25）上一行；SERVER_ERROR 有 **2 处真实引用**（SQLExceptionHandler L164/L174 `Result.error(MessageConstant.SERVER_ERROR)`）——常量不可删，只删 TODO 注释。
2. `PrivacyAspect` L123 `// TODO更多权限`——phone/email 两个隐私查看权限（PRIVACY_PHONE_VIEW/PRIVACY_EMAIL_VIEW）之后的占位注释；处置 = 改写为说明性注释（当前隐私字段仅 phone/email 两项，新增字段需同步 PRIVACY_*_VIEW 权限与切面分支），零行为变更，不扩展字段。
3. `BaseUnit` L7 `// TODO 角色测试`——挂在「线程工具类」类声明上的孤儿注释，无任何对应未完成工作（ThreadLocal RoleAO 工具已实现且被引用）；处置 = 删注释。

**归档批现状实测**：`openspec/changes/` 在途区 7 目录 = archive 本体 + 6 个已闭合卡目录（add-customer-semantic-retrieval@a2f629c 已合并 / add-customer-retrieval-baseline / add-customer-contact-retrieval / add-source-citation-and-dashboard 三张在案候补 / archive-batch-3 / clear-onboard-2-left-fragment 两个归档批自身残留）；archive/ 现有 69 目录。

## 2. owner 拍板记录（2026-10-14）

- **D1 修复范围：双堵 addUser+updateUser**——addUser 恢复被注释校验块 + updateUser 新增同校验，完整封死 roleId=1 制造路径；单测覆盖两处；MASTER_IS_ONE 启用既有常量零新增语义。
- **D2 归档清单：6 目录全核实归档**——acs/acrb/contact/scd/archive-batch-3/clear-onboard-2-left-fragment 逐个核实「已合入 master 闭合无在途内容」后 git mv 至 archive/，真 rename 提交；核实发现未闭合内容的目录不归档并在 handoff 披露。

## 3. 范围（三个任务组）

- **任务组 1 · Master 唯一性双堵**：addUser 校验恢复（roleId==1 → BaseException(MASTER_IS_ONE)，位置在 roleId null 校验后、邮箱校验前）；updateUser 校验新增（传入 roleId 显式=1 → 同拒绝，位置在 roleId set 分支前）；新建 `UserServiceImplTest` 覆盖正向/反向（addUser roleId=1 拒 / updateUser 改 roleId=1 拒 / addUser roleId=null 既有拒绝回归 / updateUser 仅改其他字段通过），纯 JVM mock 零 Docker。
- **任务组 2 · 卫生批**：三处 TODO 清理（MessageConstant L24 删注释常量保留 / PrivacyAspect L123 改写说明性注释 / BaseUnit L7 删孤儿注释），全部零行为变更。
- **任务组 3 · 归档批与门禁收尾**：六目录核实归档（git mv + 真 rename 提交，**禁 pathspec 提交**）；全量 mvn test（surefire 1248 只增不减，预计净增 +4）/ 四静态顺序跑 / 三守卫 / merge-gate 8/8；tasks 全勾留痕 + handoff。

## 4. 红线

- **B 档零迁移**：零 DDL 零新表，迁移 V1-V40 禁改。
- **零权限语义新增**：MASTER_IS_ONE 为既有常量首用、roleId=1 为既有特殊角色——行为收紧非新权限语义；零新权限常量、零 OpenEndpointRegistry 新登记、零 controller/端点改动（PermissionCoverageAuditIT 零影响）。
- **零真跑外呼**：全部测试纯 JVM mock（DASHSCOPE_API_KEY 置空），零 Docker 依赖。
- **零冻结契约触碰**：contracts-frozen.md 不解冻；SERVER_ERROR 常量保留（2 处引用在案）。
- **surefire 只增不减**（基线 1248）；新代码中文 Javadoc 标注「harden-admin-uniqueness-and-hygiene 任务 x.x」；四静态与全量 test 禁并发顺序跑；merge-gate 8/8。
- **归档 mv 禁 pathspec 提交**：git mv 后以目录级暂存正常 commit，确保 rename 保真（提交规范先例）。

## 5. 裁决假设（owner 可否决）

1. roleId=1 语义 = 终极管理员（超管），唯一性拒绝口径覆盖 addUser 与 updateUser 两处（D1 拍板）；updateUser 仅当传入 roleId 显式=1 时拒绝，roleId=null 不动角色（既有「null 表示不修改」行为保持）。
2. SERVER_ERROR 常量保留（SQLExceptionHandler 2 处引用），只删其上 TODO 注释。
3. PrivacyAspect 处置 = 注释改写非字段扩展（新增隐私字段属功能扩展留后卡）。
4. BaseUnit 孤儿注释直接删，无关联工作。
5. 归档判定标准 = 两件套在案 + 卡已合入 master + 无在途内容；未过判定的目录不归档并在 handoff 披露原因。
6. updateUser 自我提权面（个人信息端点 vs 管理端点）不在本卡扩查——本卡只堵 roleId=1 写入路径，端点权限矩阵现状零改动。
7. 种子内既有 roleId=1 用户不受影响（校验只拦新增写入，不迁移不清理存量）。

## 6. 验收

- 任务组 1-3 全勾且每勾留痕；addUser/updateUser roleId=1 拒绝测试在案（UserServiceImplTest 新建，surefire 1248→1252+）；三处 TODO 清理后全仓 `grep -rn "TODO" src/main/java` 显性 TODO 零残留（新增代码不得引入新 TODO）；归档后 openspec/changes/ 在途区仅剩本卡目录与 archive 本体；四静态 0 / 三守卫绿 / merge-gate 8/8；handoff 落 work/handoff-harden-admin-uniqueness-and-hygiene.md。
