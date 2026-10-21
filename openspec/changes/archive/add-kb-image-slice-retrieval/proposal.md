# 提案 · add-kb-image-slice-retrieval（知识库图片切片检索 MVP，方案 B 文本降维闭环，A 档）

## 拍板记录（2026-10-21 Owner 拍板）

多模态图文混合检索专题首卡走**方案 B**：图片→VLM 描述→描述文本切片走现有文本 embed 入库→命中后 SourceReference=IMAGE 受控解冻回显。真多模态（图文共享向量空间 + multimodal-embedding + collection 路由）作为后续升级卡独立立项，本卡不预埋。

## 为什么（2026-10-21 权威树 master@b7b750f 实读）

- 现状是文本降维形态：摄取侧 `PdfVisionTranscriber` 只做**整页**视觉转写（图像页→文字）；查询侧 `AiChatImageUnderstandingService.generateImageVector:191-213` 是理解文本合成串走文本 embed。**PDF 内嵌图片对象从未被提取为独立切片**，图片资产不入库、检索命不中、引用回不来。
- 融合侧零改动即可承接：`KnowledgeRetrievalServiceImpl` 图文双路加权（文本 0.70/图片 0.30）与 `recall` 查同一文本向量空间——描述文本切片入库后天然可被现有文本路与图片理解向量召回。
- 契约面有成熟先例：`SourceReference.sourceType` 已三次受控解冻（CUSTOMER / BUSINESS_ACTIVITY / SALES_OPPORTUNITY，复用十字段零结构改动，spec-delta 登记）。

## 做什么

1. **摄取侧图片资产化**（任务组 1）：
   - PDFBox 提取 PDF **内嵌图片对象**（PDResources/XObject，非整页渲染）；仅 PDF，Office 等格式留后续。
   - 图片资产落盘：对齐现有知识库文档文件存储基建（执行时侦察 DocumentService 存储形态后对齐，不新造对象存储）；资产需可由「文档删除/物理双清」联动清理（对齐 C-11 形态）。
   - 每图 VLM 描述：复用 `ModelProvider.vision` + TokenUsageType.VISION 计量 + 质量闸门 + 预算帽形态（对齐 `PdfVisionTranscriber`：开关/页数帽/闸门失败回退）。
   - 描述文本作为切片入现有 chunk/embed 链路（父子块切分不适用，单图单切片）；切片→图片资产的追溯引用写入 chunk 元数据/payload（执行时侦察 VectorRecord payload 形态对齐；**零迁移优先**——能复用现有列/payload 不加列，确需资产表/新列才走下一 Flyway 号并在执行报告论证）。
   - 开关 `rag.retrieval.image-slice-ingest.enabled` **默认 false**，KB 维度灰度（对齐 vision-pdf 形态）；每文档图片数帽（默认 5，动态配置）。
2. **检索回显**（任务组 2）：
   - `SourceReference.sourceType` 扩展 `IMAGE`（D3 受控解冻先例形态：`documentId`=图片资产 id、`filename`=图片描述摘要、`chunkId`=`IMAGE:{资产id}`），复用十字段零结构改动；spec 增量登记 `specs/knowledge-rag/spec-delta.md`；`contracts-frozen.md` 解冻记录追加（对齐 :139-141 三先例格式）。
   - SSE `sources` payload 结构零改动；图片资产读取端点需鉴权（对齐现有文档下载端点权限形态，KB 归属/成员过滤覆盖图片切片——检索命中天然走 knowledgeBaseId filter）。
3. **前端最小渲染**（任务组 3）：sources 里 sourceType=IMAGE 渲染缩略图（点击放大可选），对齐现有 sources 渲染分支；frontend/ diff 走 batch3 D1 先例裁决记录。
4. **测试与验证**（任务组 4）：单测（提取/闸门/帽/回显映射）+ Testcontainers IT（图片切片入库→检索命中→SourceReference=IMAGE 断言）+ 契约门禁（ParityTest/springdoc 若有 diff 走 `--update` 唯一入口）；**真外呼真跑不在本卡**（VLM 描述链路用 mock/录制的确定性测试），生产灰度真跑由 Owner 另行授权。

## 红线

摄取开关默认关（默认行为零变化）/ 零新 Maven 依赖（PDFBox 已有）/ 冻结契约只走受控解冻（spec-delta + contracts-frozen 登记，不自造来源类型）/ 零迁移优先（确需才走下一号）/ 图片资产权限不得宽于所属 KB / 严禁 git push、merge、reset/rebase/amend、删分支 / 基线只增不减（当前 304/1894 + 34/124/7）。

## 档位

**A 档**（判据：真外呼开关形态 + 冻结契约解冻）——执行自证 → 独立复核子 Agent（Owner 亲发）→ 主 Agent 核验审计 → 合并授权。复核聚焦四判据：迁移（若有）、权限语义（图片资产/切片不宽于 KB）、真跑（默认关+无未授权外呼）、冻结契约（解冻登记合规）。

## 验收口径

定向 + 全量 `mvn -B -ntp clean verify`（Docker 实测）+ 树净单次 `check-test-baseline.sh --update` + `merge-gate.sh` 10/10 + 前端 lint/type-check（若 frontend/ 有 diff）；handoff 含存储对齐论证、零迁移论证（或迁移论证）、解冻登记链接、开关矩阵。停步：未 merge 未 push。
