# 提案 · fix-engineering-debt-loop-write-batch（工程债循环写收口批：级联 / 关联维护 / 用户批删）

## 为什么
池账 #14 批余三债（A-6/A-8/A-10）同属「循环写放大」家族：批量删除联系人每 id 触发一轮全量级联扫描且自调用绕代理、活动关联维护 8 处 check-then-act 循环放大 2N 条 SQL、用户批删已批量查出仍逐条 update 且置空从未生效。上卡（nplus1-loop-clone）收口读侧与软删侧，本卡收口剩余写侧；均零迁移零依赖。

## 做什么（三点合一）
1. **A-6 级联批量化下沉**：`ForeignKeyDeleteUtil.deleteCascade`（:42-77）本就吃 `List<Long>` 且每层 `wrapper.in(parentIds)`——仅 `logicalDeleteByCascade`（:86-95）单 id 包装所致。新增批量入口 `logicalDeleteByCascadeBatch(Class, List<Long>)`（级联单趟 + 主表逻辑删改单条 `update(null, wrapper.set(isDeleted,true).in(id, ids))`）；`CustomerContactServiceImpl.batchLogicalDeleteByIds`（:429-441）删自调用循环（:437-439）改一次委托；单条入口 `logicalDeleteById` 保留。等价点：级联层级与顺序不变、返回计数含义不变（重复 id 以实际行数计，单列论证）、外层 @Transactional 语义不劣化。写集面：2 文件。
2. **A-8 活动关联维护集合化**：`BusinessActivityRelationSupport` 8 处循环（:42/:59/:87/:105/:128/:159/:187/:202，exists+insert/delete 逐条）→ 集合一次存在性比对（一次 in 查询取已存在集合，list-vs-set 比对）+ 批量写（多值 insert mapper 方法或等价形态由执行定，允许新增 mapper 批量方法）。错误语义逐路径不变：add 重复 → PARAM_FORMAT_ERROR 同文案、delete 缺失 → NOT_EXISTS 同码同文案、null 项跳过保留、**请求内重复 id 旧语义保留**（add 第二轮抛重复 / delete 第二轮抛不存在）；既有 `BusinessActivityRelationSupportTest`（50002 收敛卡产物）必须保持全绿；check-then-act 竞态不劣化论证。写集面：1 源文件 + mapper。
3. **A-10 用户批删单条化 + 置空缺陷修复**：`UserServiceImpl.deleteById`（:287-311）循环逐条（:303-308）→ 单条 `update(null, wrapper.set(status,3).set(email,null).set(phone,null).in(id, id))`；预检闸（:292-300）保留（重复 id 行为不变）；@CacheEvict(allEntries)+@Transactional 保留。**立项实测拍板项**：MP 默认 NOT_NULL 字段策略（application.yml:204-206 无 update-strategy 覆盖、UserEntity email/phone 无 @TableField 注解）→ 旧 `setEmail(null)/setPhone(null)` 从未真置空，已删用户占用 uk_email/uk_phone 致无法复用（潜在缺陷）；新实现显式 set 真置空＝修复该缺陷（推荐，符合代码原意与唯一键语义），行为差异在 handoff 单列论证。写集面：单文件。

## 红线
零数据库迁移 / 零新依赖 / 零新增错误码常量 / 不改冻结契约 / 不动前端（错误码与文案均不变）/ 基线只增不减。A-8 允许新增 mapper 批量方法（接口+SQL），不得引入新依赖。

## 档位
**A 档**：三处均客户端可见写路径，含事务/级联语义、check-then-act 集合化改写与一处行为修复拍板，超 B 档门禁自证面，需独立复核。

## 验收口径
定向测试全绿（A-6 批量入口单趟断言+委托断言；A-8 8 方法错误语义+请求内重复+null 跳过+批量写形态；A-10 单条 SET/IN 断言+预检闸+置空新语义）+ `mvn -B -ntp clean verify` 全绿 + 树净单次 `check-test-baseline.sh --update` + `merge-gate.sh` 10/10 + handoff 含三模块证据与等价性论证。严格停步：未 merge 未 push，待 A 档独立复核。
