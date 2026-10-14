# tasks — harden-admin-uniqueness-and-hygiene

> 红线：**B 档零迁移**（零 DDL 零新表，迁移 V1-V40 禁改）；**零权限语义新增**（MASTER_IS_ONE 既有常量首用、roleId=1 既有特殊角色，行为收紧非新语义；零新权限常量、零 OpenEndpoint 登记、零 controller 改动）；**零真跑外呼**（全部测试纯 JVM mock，零 Docker）；**零冻结契约触碰**（contracts-frozen.md 不动；SERVER_ERROR 常量保留 2 处引用在案）；surefire 只增不减（基线 1248）；新代码中文 Javadoc 标注「harden-admin-uniqueness-and-hygiene 任务 x.x」；四静态与全量 test 禁并发顺序跑；merge-gate 8/8；**归档 git mv 禁 pathspec 提交**（rename 保真）。

## 任务组 1 · Master 唯一性双堵

- [x] 1.1 addUser 校验恢复：`UserServiceImpl#addUser` L89-92 注释块转正——roleId null 校验后、邮箱校验前，`if (Objects.equals(userDTO.getRoleId(), 1L)) { throw new BaseException(MessageConstant.MASTER_IS_ONE); }`（等值比较以 roleId 实际类型为准），删除原注释块与 TODO 行，中文 Javadoc 标注任务号
      依据：UserServiceImpl.java addUser（L90-98）恢复 roleId==1L 抛出 BaseException(MessageConstant.MASTER_IS_ONE)，原注释块与 TODO 行已删除，方法与内部代码均带中文 Javadoc/注释标注「harden-admin-uniqueness-and-hygiene 任务 1.1」
- [x] 1.2 updateUser 校验新增：`UserServiceImpl#updateUser` L234 roleId set 分支前——`userDTO.getRoleId() != null && roleId==1` 时抛 `BaseException(MessageConstant.MASTER_IS_ONE)`；roleId=null 不动角色（既有「null 表示不修改」行为保持）；中文 Javadoc 标注任务号
      依据：UserServiceImpl.java updateUser（L239-245）在 roleId set 分支前增加 Objects.equals(userDTO.getRoleId(), 1L) 校验并抛出 MASTER_IS_ONE，保持 roleId=null 不修改语义，带中文 Javadoc/注释标注「harden-admin-uniqueness-and-hygiene 任务 1.2」
- [x] 1.3 新建 `UserServiceImplTest`（纯 JVM mock 零 Docker）至少 4 例：addUser roleId=1 拒绝（MASTER_IS_ONE）/ updateUser 传入 roleId=1 拒绝 / addUser roleId=null 既有拒绝回归（ROLE_NOT_EXISTS 口径按实测）/ updateUser 仅改 realName 通过（roleId=null 不触发校验）；mock 面 userMapper/sysDeptMapper 按 UserServiceImpl 实际依赖
      依据：新建 UserServiceImplTest.java（纯 JVM Mockito + TableInfoHelper 初始化），4 例实测全部通过（shouldRejectAddUserWhenRoleIdIsOne / shouldRejectUpdateUserWhenRoleIdIsOne / shouldRejectAddUserWhenRoleIdIsNull / shouldAllowUpdateUserWhenRoleIdIsNull），单测耗时 2.274s，0F 0E 0S

## 任务组 2 · 卫生批（三处 TODO 清理，零行为变更）

- [x] 2.1 三处清理：MessageConstant L24 删 `// TODO 记得删`（SERVER_ERROR 常量保留不动）/ PrivacyAspect L123 `// TODO更多权限` 改写为说明性注释（当前隐私字段仅 phone/email，新增需同步 PRIVACY_*_VIEW 权限与切面分支）/ BaseUnit L7 删孤儿注释 `// TODO 角色测试`；清理后全仓 `src/main/java` `// TODO` 归零（2026-10-14 立项实测恰 4 处全在本卡范围，新增代码不得引入新 TODO）
      依据：MessageConstant.java 删 L24 TODO 并保留 SERVER_ERROR 常量；PrivacyAspect.java L123 改写为说明性注释；BaseUnit.java L7 删孤儿注释；全仓 src/main/java 大写 // TODO 彻底归零（实测仅剩 CustomerContactListener:51 小写历史注释与 SQLExceptionHandler:104 Javadoc 文本）

## 任务组 3 · 归档批与门禁收尾

- [x] 3.1 六目录核实归档：add-customer-semantic-retrieval / add-customer-retrieval-baseline / add-customer-contact-retrieval / add-source-citation-and-dashboard / archive-batch-3 / clear-onboard-2-left-fragment——逐个核实（两件套在案 + 卡已合入 master + 无在途内容）后 `git mv openspec/changes/<name> openspec/changes/archive/`；目录级暂存正常 commit（**禁 pathspec 提交**，确保 rename 保真）；未过判定标准的目录不归档并在 handoff 披露原因；归档后在途区仅剩本卡目录与 archive 本体
      依据：六目录逐个核实（两件套在案、最新 commit 均为 master 祖先节点、无在途未跟踪内容），git mv 后常规暂存单笔提交 276f4ae（13 文件 100% rename，0 insertions, 0 deletions，禁 pathspec 提交保真）；归档后在途区仅剩 harden-admin-uniqueness-and-hygiene 与 archive 本体
- [x] 3.2 全量门禁：`mvn -B -ntp test` 0 失败（surefire 1248→1252+ 只增不减）；checkstyle/spotbugs/spotless/pmd 顺序跑全 0；三守卫（pmd-baseline/bijection/test-baseline）+ merge-gate 8/8；基线 --update 仅当 surefire 净增（树净状态单次跑，MAJOR-1 教训：禁复跑覆盖戳）
      依据：全量 surefire 实测 1252 绿（N=1252, F=0, E=0, S=0，耗时 04:08 min，净增 +4）；checkstyle/spotbugs/spotless/pmd 四静态顺序跑全 0；pmd-baseline/bijection/test-baseline 三守卫全部 EXIT 0；树净状态执行 --update 单笔提交 cdc6992（无 -dirty）；merge-gate 8/8 全部 PASS（EXIT 0）
- [x] 3.3 tasks 全勾 + 每勾下一行 6 空格依据行（双堵代码位置/测试实测数字/TODO 归零 grep 留痕/归档前后目录清单/门禁实测数字），handoff 落 work/handoff-harden-admin-uniqueness-and-hygiene.md（含偏差披露节）
      依据：tasks.md 7 勾全亮附 6 空格依据行；handoff 快照文件落盘于 work/handoff-harden-admin-uniqueness-and-hygiene.md，包含完整笔清单、门禁实测数据、归档目录核验与偏差披露节
