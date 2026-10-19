# 提案 · fix-engineering-debt-nplus1-loop-clone（工程债三合卡：N+1 / 循环软删 / 双轨映射）

## 为什么
池账 v2 §2 面A 三条 A 档工程债（A-7/A-9/A-1）同属「SQL 放大与双轨维护」家族：分页 N+1 每页 3N+1 次用户查询、批量软删 2N 条 SQL 双胞胎逐字同形、唯一约束字段映射 switch 与 @Value 配置双轨并存且挂着全 src/main 唯一真 TODO。单项开卡过碎，三合一一次收口；三者均零迁移零依赖、风险低价值中。

## 做什么（三点合一）
1. **A-7 UserHandover 分页 N+1**：`UserHandoverServiceImpl.convertToVOPage`（:202-213，循环逐行 `buildHandoverVO`→`getUserName` :157-166，每行 3 次 `userMapper.selectById`，每页 3N+1 查询）→ 页内 from/to/operator 三类 id 去重后 `selectBatchIds` 单次建 Map 回填；单条路径（executeHandover）行为不变。写集面：单文件。
2. **A-9 Invoice/Payment 批量软删双胞胎**：`InvoiceInfoServiceImpl.deleteByIds`（:155-171，循环 selectById+updateById 共 2N 条 SQL，置 status=1）与 `PaymentRecordServiceImpl.deleteByIds`（:174-190 同形，置 paymentStatus=2）→ 各自单条 `LambdaUpdateWrapper` set 状态 + `in(id, idList)` 批量更新，两处形态对齐。等价性立项实测：两实体零 @Version/@TableLogic（InvoiceInfoEntity:56 / PaymentRecordEntity:55 纯状态字段）、全仓 src/main 零 MetaObjectHandler 自动填充；唯一行为差异点＝重复 id 输入下旧实现重复计数、新实现按实际行数（以新为准，handoff 论证）。写集面：2 文件。
3. **A-1 SQLExceptionHandler 双轨收敛**：唯一约束冲突路径手写 switch（`getFieldNameByIndex` :78-102，uk_phone→手机号 等 7 项）与 `@Value` 配置映射（`slz.database-field-map`，application.yml:220-240，现键空间为列名）双轨并存 → 7 个索引名键并入既有配置映射成单源（值与 switch 逐项一致），删 switch，:104 TODO 标记随收敛消除（维护说明单点化）；duplicate-key（含 composite-unique-index-map 组合索引路径，application.yml:241-244）/ FK / SQL 三 handler 逐路径文案不变（default「数据」/「未知字段」各自保留）。写集面：handler + application.yml。

## 红线
零数据库迁移 / 零新依赖 / 零新增错误码常量 / 不改冻结契约 / 不动前端（三模块错误码与文案均不变，error-code-map.ts 无涉）/ 基线只增不减。配置变更仅限 application.yml 的 slz.database-field-map 增 7 键。

## 档位
**A 档**：三处均触及客户端可见行为路径（分页接口、批量删除接口、全局异常映射），含 @Value 配置注入路径改造，超 B 档门禁自证面，需独立复核。

## 验收口径
定向测试全绿（A-7 断言 selectBatchIds 恰一次/selectById 零调用/三名字正确；A-9 两服务断言单条 update 的 SET+IN 且逐条调用零；A-1 三 handler 逐路径文案断言）+ `mvn -B -ntp clean verify` 全绿 + 树净单次 `check-test-baseline.sh --update` + `merge-gate.sh` 10/10 + handoff 含三模块证据与等价性论证。严格停步：未 merge 未 push，待 A 档独立复核。
