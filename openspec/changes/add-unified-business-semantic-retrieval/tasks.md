# add-unified-business-semantic-retrieval — 任务清单

> 提交粒度与任务组对齐：每任务组一笔提交，分支 `feature/add-unified-business-semantic-retrieval` 自权威树 master 检出。
> 依据行格式：6 空格 + 实测证据（命令 + 关键数字/哈希）。未实际执行的步骤禁止打勾。

## 任务 1 · 契约解冻与 spec-delta（契约层）

- [x] 1.1 卡内 `specs/knowledge-rag/spec-delta.md` 落地：声明 `SourceReference.sourceType` 支持 `"CONTRACT"` 与 `"CONTACT_TASK"`，明确字段映射规约
      依据：git log -1 --stat 849d999 落盘 spec-delta.md，定义 CONTRACT/CONTACT_TASK 来源规约与多实体编号顺延
- [x] 1.2 `spec/changes/add-crm-rag-fusion-platform/contracts-frozen.md` 追加受控解冻变更条目与 Javadoc 同步
      依据：git log -1 --stat 8d83f35 contracts-frozen.md 追记 2026-10-16 受控解冻条目，SourceReference Javadoc 补齐取值

## 任务 2 · 全站增量向量化异步化重构（异步解耦与消灭技术债）

- [x] 2.1 存量增量 Hook 升级异步：重构 `CustomerSemanticHookSupport` 与 `BusinessActivitySemanticHookSupport`，注入 `embeddingTaskExecutor` 全面改为事务后异步派发，彻底消除主业务线程阻塞
      依据：git log -1 --stat 9734265 存量两 hook 升级 runAfterCommitAsync，Customer/BusinessActivity 服务注入线程池异步派发
- [x] 2.2 新增 `ContractSemanticHookSupport` 与 `ContactTaskSemanticHookSupport`：在合同与跟进任务增删改提交后异步派发向量重嵌与删除，异常 WARN 隔离
      依据：git log -1 --stat 9734265 新增 Contract/ContactTask 异步 hook，Contract/ContactTaskServiceImpl 全链路接线

## 任务 3 · 合同与任务摄取服务及回埋端点（摄取层）

- [x] 3.1 新增 `ContractSemanticTextSupport` 与 `ContractIngestionService`：拼装合同信息并附带订单明细与回款履约附属段，打标 Payload 写入 `CrmEntityVectorStore`
      依据：git log -1 --stat 7e3a302 ContractIngestionService + 订单明细/回款附属段拼装落盘，Payload 打标完整
- [x] 3.2 新增 `ContactTaskSemanticTextSupport` 与 `ContactTaskIngestionService`：拼装任务主题与富文本描述，打标 Payload 写入 `CrmEntityVectorStore`
      依据：git log -1 --stat 7e3a302 ContactTaskIngestionService + task_content 富文本拼装落盘，Payload 打标完整
- [x] 3.3 存量回埋端点：新增 `POST /contract/reingestAll` 与 `POST /contactTask/reingestAll` 挂既有权限注解；若契约检查触发漂移，同步更新 `frontend/openapi.yaml`
      依据：git log -1 --stat 7e3a302 回埋端点就位挂权限注解，check-openapi-consistency.sh 回写 openapi.yaml 消除漂移

## 任务 4 · 检索五实体全覆盖与前端引用深度跳转（检索与前端层）

- [x] 4.1 检索服务升级：`BusinessRetrievalService` 接入 `contract` 与 `contact_task`，实现 5 业务实体同轮多路召回、数据权限闸与纵深防御复核
      依据：git log -1 --stat bc3f334 BusinessRetrievalService 扩展五实体多路召回，严格同锚 DataScopeTablePermissions
- [x] 4.2 前端引用跳转闭环：修复 `AiAssistantDrawer.vue` 业务实体来源判断，实现客户/商机/合同/任务/活动的精准深度跳转，杜绝误调知识库接口
      依据：git log -1 --stat bc3f334 AiAssistantDrawer.vue 覆盖五实体判定与五深度跳转路由，杜绝 /knowledge/chunk 404

## 任务 5 · 单元测试与门禁收尾（门禁层）

- [x] 5.1 权限反向测试矩阵：覆盖合同与跟进任务四角色权限（归属人本人可查、本部门上级可查、跨部门同事绝不可查、管理员全可查）
      依据：mvn -B -ntp test -Dtest=BusinessRetrievalServiceTest 退出码 0，23/23 绿（五实体四角色与跨部门反向物理不可达）
- [x] 5.2 摄取拼装、异步隔离、全量回埋及存量异步改造单测（纯 JVM，零真跑外呼）
      依据：mvn -B -ntp test -Dtest=*IngestionServiceTest,*SemanticTextSupportTest,*SemanticHookSupportTest 退出码 0，全绿
- [x] 5.3 前端质量门禁：前端单元测试与类型检查全绿（`pnpm lint:check` + `pnpm type-check:check` + `pnpm vitest run`）
      依据：pnpm lint:check && pnpm type-check:check && pnpm vitest run 全通过，vitest 179 passed (17 files)
- [x] 5.4 `mvn -B -ntp test` 全绿，surefire 数量只增不减（基线 1342），树净时单次 `--update` 基线
      依据：mvn -B -ntp clean verify 退出码 0，surefire 1391（净增 49），基线 --update 写入 1342→1391（source-revision=f75c5fe 树净）
- [x] 5.5 四静态（checkstyle/spotbugs/spotless/pmd）顺序跑全绿 + 三守卫 + `scripts/merge-gate.sh` 退出码 0
      依据：bash scripts/merge-gate.sh 退出码 0 全绿（8/8：unit 1391/spotbugs 0/pmd 0/baseline/frontend-unit 179/hook/bijection/pmd-baseline）
