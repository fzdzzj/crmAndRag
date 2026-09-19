# RAG 工作区优化交接文档

> 交接对象：准备对 `c:\Users\fzdzzj\Desktop\rag` 做 **pipeline 优化 + RAG 优化** 的人/agent。
> 本文自包含：读完即可动手，无需回溯之前的分析过程。
> 建议把本文件放到 `c:\Users\fzdzzj\Desktop\rag\优化交接.md`。

---

## 0. 这是什么工作区

一个**关于 RAG 的学习知识库**，同时它**本身就是一个迷你 RAG 系统**。两层含义：

1. **内容层**：`rag-kb/` 下 13 章 Markdown，是对工作区 RAG 学习材料（5 套中文课件 + 1 份 PPTX + 4 篇英文论文 + 10 张截图 + 21 道外部面试题）的精炼产物。
2. **系统层**：`index.json`（词法路由）+ `glossary.json`（术语）+ `AGENTS.md`（AI 使用契约）+ `[S*]/[W*]/[X]` 溯源标记，构成一个"给 AI agent 读"的检索系统。

优化因此分两条链路（沿用 `rag-kb/00-RAG知识地图.md` 的框架）：**离线索引链路（pipeline）** 与 **在线查询链路（RAG 检索/使用）**。

---

## 1. 现状与证据（已核实，勿重复推导）

### 1.1 目录结构
```
c:\Users\fzdzzj\Desktop\rag\
├── *.pdf / *.pptx / 屏幕截图*.png      # 原始材料（只读，禁止修改）
├── AGENTS.md                          # 工作区入口
├── _extract\                          # 抽取脚本 + 中间产物（原料，非知识库）
│   ├── extract.py                     # PDF 文本层抽取（pypdf + pdfminer 取长）
│   ├── render.py                      # 课件 PDF → PNG（pypdfium2, scale=2.0/~144DPI, cap 1600px）+ PPTX 文本
│   ├── pptx_extract*.py / pptx_media.py
│   ├── txt\ + report.txt              # 文本层抽取结果 + 质量报告
│   ├── pages\<课件名>\pNN.png         # 132 张课件逐页 PNG
│   └── pptx_media\*.png               # 13 张 PPTX 整页图
└── rag-kb\                            # 知识库正文
    ├── 00..12-*.md                    # 13 章（00 知识地图 … 12 工程落地/面试）
    ├── AGENTS.md                      # AI 使用契约（文件地图/引用约定/回答规范）
    ├── README.md / sources.md         # 人类视角 / 出处与提取质量说明
    ├── index.json                     # 由 build_index.py 生成：13 文档/77 主题/204 关键词/段落(带行号)
    ├── glossary.json                  # 术语表（180KB）
    ├── build_index.py                 # front matter → index.json（含 --check 校验）
    ├── verify_kb.py                   # 9 项跨文件一致性校验
    └── _drafts\                       # 子代理逐页视觉精读草稿（正文冲突时以草稿为准）
```

### 1.2 pipeline 数据流
```
原始 PDF/PPTX/PNG
  → extract.py（文本层）+ render.py（课件渲成 PNG）
  → 【人工/子代理视觉精读 PNG】→ _drafts\*.md
  → 提炼成 rag-kb\00..12-*.md（带 YAML front matter + [S/W/X] 标记）
  → build_index.py（front matter → index.json 词法路由）
  → verify_kb.py（结构一致性校验）
  → AGENTS.md 契约：AI 查 index.json 路由 → 读对应 ## 段 → 带标记作答
```

### 1.3 关键证据：抽取质量两极分化（`_extract/report.txt` + `sources.md §一`）
| 材料 | 文本层 | 实质载体 | 处理方式 |
|---|---|---|---|
| 4 篇英文论文 | **优**（46k–141k 字符） | 文本层 | extract.py 直接可用 |
| 5 套中文课件 | **差/极差**（0.7k–6k 字符） | **页面图像** | render.py → PNG → **人工视觉精读** |
| PPTX | 极差（~0.8k 字符） | 幻灯片图像 | pptx_media → 视觉精读 |
| 10 张截图 | 无文本层 | 图像 | 视觉精读 |

> 例：`索引优化技术.pdf` 15 页仅 **731 字符 / 234 汉字**——整节课的机制全在图里。
> **这就是 pipeline 的最大瓶颈**：图片型材料依赖人工视觉精读，代价高、带"（截图不清）"不确定性（`sources.md §四`）。

### 1.4 RAG 系统层现状
- 路由是**纯词法**：`index.json` 的 `topic_map`(77) / `keyword_map`(204) / `source_map` 命中文件；`sections[]` 有 `##` 段标题 + 行号，但**不作为独立可检索单元**。
- **无 embedding、无语义召回、无重排**。
- `verify_kb.py` 只校验**结构一致性**（死链/索引同步/术语表/front matter/不确定标记计数），**没有检索质量评估**（不验"某问题是否路由到正确文档/段落"）。

---

## 2. 优化清单（已排优先级；依据是 rag-kb 自己教的 ch04/05/06/08）

### 2.1 Pipeline（离线索引链路）
| 编号 | 优化 | 现状/证据 | 代价 | 优先级 |
|---|---|---|---|---|
| **A1** | **视觉转写自动化**：PNG 批量过 Qwen-VL → 结构化 md，替代/辅助人工精读 | 现全靠人工读 145 张 PNG | VLM 调用成本 + 仍需抽检小字 | 🔴 **脚本已建，见 §3** |
| **A3** | **抽取质量闸门 + 自动分流**：extract.py 加 cjk 比/字符每页阈值，自动判"文本可用 vs 必须走视觉"，把 `sources.md §一` 的人工分类表变脚本产出 | 现"取 pypdf/pdfminer 较长者"，长≠好 | 几乎无 | 🔴 |
| **A2** | **渲染保真自适应**：文字密集/代码截图页提到 ~200DPI/2400px | render.py 固定 scale=2.0/cap1600px；AGENTS.md §3 已警告"极小文字误读" | PNG 更大、token 更多 | 🟡 |
| A4 | 代码截图专用 OCR（`sources.md §四.3`：代码是截图、转写不保证可运行） | 无 | 中 | 🟢 |
| A5 | 公式保留（`sources.md §四.1`：PDF 公式退化为线性文本） | 无 | 中 | 🟢 |

### 2.2 RAG（在线查询链路）——以 [S11] 17 方案为候选集（用户指定：rag 优化主要考虑这 17 个）

**tier 定位**（用 deck 的落地选型矩阵）：rag-kb 规模属“敏捷探索”档（13 篇），但消费方是 AI agent 且单章大（06=89KB），故取“**敏捷档为底 + 从生产级档借三件**”：以 **02 语义切分 / 03 大小块 / 14 层次化** 为骨架，+ **16 混合 + 08 Rerank + 10 压缩** 提质；**不上 11/12/13/17**（理由见下表）。

**17 方案对 rag-kb 的适用性**（🔴高 / 🟡中 / 🟢低·暂不做）：
| # | 方案 | 适用 | 对 rag-kb 的理由 |
|---|---|---|---|
| 16 | Hybrid Search | 🔴 | 现状**纯词法**(keyword_map)；加 embedding 语义路 + RRF 融合，补同义/改述漏召（=旧 B1） |
| 03 | Small-to-Big | 🔴 | 现状路由到“文件”(06=89KB 太大)；改检索 `##` 段(小块)、生成带父章(大块)（=旧 B2）；sections[] 已有行号，改造成本低 |
| 08 | Rerank | 🔴 | 多路召回后精排（=旧 B3） |
| 10 | Context Compression | 🔴 | 章节大，召回后压缩重点再喂 LLM，省 token + 防注意力分散 |
| 06 | Document Augmentation | 🟡 | 为每段反向生成衍生问题集绑定向量化；[W*] 面试题是天然衍生问题 |
| 14 | Hierarchical Index | 🟡 | index.json 已是 docs[](summary)+sections[] 双层雏形，正式化为 Summary+Chunk 双层（=旧 B2 另一半） |
| 04 | Context Enriched | 🟡 | 命中段带 [前置][命中][后置] 邻居，与 03 配套 |
| 05 | Chunk Header | 🟡 | 每段加【章节主旨+路径】头，提升碎片块全局视野 |
| 02 | Semantic Chunking | 🟡 | `##` 已是语义边界；段内再按转折词/话题跃迁细化 |
| 07 | Query Transformation | 🟡 | AI agent 本身是查询理解者，改写/回退/子问题拆解价值中等 |
| 15 | HyDE | 🟡 | 词汇差异大时先猜答案再匹配真证据 |
| 09 | Sentence Window | 🟡 | 长段命中核心句后滑窗扩展 |
| 01 | Simple RAG | 🟢 | 基线对照，非升级项 |
| 11 | Feedback Loop | 🟢 | 无点赞/踩等反馈信号，暂无数据源 |
| 12 | Self-RAG | 🟢 | 增复杂度；等 B4 评估有基线后再考虑 |
| 13 | KG RAG | 🟢 | 13 篇无多跳实体关系需求，过度 |
| 17 | CRAG | 🟢 | 封闭库无外部 web 兜底；其“低相关→诚实零命中” rag-kb 已用 D16 实现 |

**RAG 侧执行序**（评估先行，`08-评估` 路径B 铁律：“先有度量再优化，别凭感觉调参”）：
1. **B4 检索评估集**（黄金集 = 21 道[W*] + 各章 topics → 应命中段；先有基线）——**必须最先做**，否则下面都无法判断是否真变好。
2. **03 + 14**（段级 + 层次索引，改 index.json）。
3. **16**（embedding 语义路 + RRF）。
4. **08**（rerank）。
5. **10**（大章节压缩）。
6. **06 / 04 / 05** 增量 → **02 / 07 / 15 / 09** 按需。
7. **11 / 12 / 13 / 17** 暂不做（理由见上表）。
8. **C1 索引防漂移**：`build_index.py --check` + `verify_kb.py` 挂 git pre-commit / CI。

> ⚠️ 口径提醒：deck 的数字（“扫清 80% 漏洞”“初筛 Top50→精选 Top5”“压缩到 200 字”）是 **[S11] 自身口径、无外部印证**（`00-知识地图 §3` 可信度提示）——方案对比时当“工程经验口径”，**别当实测基线**。

> 核心原则（`00-知识地图 §0`）："检索质量是整条链路的能力上限，调优主战场在检索侧，不是换更强的 LLM"。

---

## 3. A1 已交付：`vlm_transcribe.py`（Qwen-VL 自动转写）

**状态**：脚本已写好，**暂存在 `d:\code\crmAndRag\_vlm_transcribe.py`**（助手沙箱不能写 rag 目录，需你移过去）。**尚未实跑**（助手侧网络出口未验证）——请先试点。

**移动到位**（在你自己的 shell 里跑，非助手沙箱）：
```powershell
Move-Item "d:\code\crmAndRag\_vlm_transcribe.py" "c:\Users\fzdzzj\Desktop\rag\_extract\vlm_transcribe.py"
Move-Item "d:\code\crmAndRag\_rag优化交接.md"     "c:\Users\fzdzzj\Desktop\rag\优化交接.md"
```

**它做什么**：读 `_extract/pages/*/pNN.png` + `_extract/pptx_media/*.png`，逐张过 Qwen-VL（DashScope 兼容端点，默认 `qwen-vl-max`），转写成结构化 md 落 `_extract/vlm/<课件名>/pNN.md`。**幂等**（跳过已转写）、失败指数退避重试、`--limit/--deck/--dry-run/--root/--out` 可控。

**转写提示词已内置 KB 约定**：逐字提取 / 代码用```包裹 / 架构图文字+箭头描述 / 表格转 md / **无法确认的小字标"（截图不清）"、禁止猜测补全** / 不编造数字·模型名·文献（对齐 `AGENTS.md §6`、`sources.md §四`）。

**运行步骤**：
```powershell
cd c:\Users\fzdzzj\Desktop\rag
$env:DASHSCOPE_API_KEY="sk-你的key"          # 只进会话环境变量，别写进任何文件
# 1) 先 dry-run 看发现对不对（不花钱）
python _extract\vlm_transcribe.py --dry-run
# 2) 试点 2 页（最差的课件），验证网络通 + 转写质量
python _extract\vlm_transcribe.py --deck 索引优化技术 --limit 2
#    → 打开 _extract\vlm\索引优化技术\p01.md 对照原 PNG 核质量
# 3) 质量 OK 再全量（145 张，幂等可断点续跑）
python _extract\vlm_transcribe.py
```

**跑完做什么**：把 `_extract/vlm/` 的机器转写与 `_drafts/` 的人工精读**交叉核对**——VLM 补人工漏读的、人工纠正 VLM 误读的，最终回填正文时仍守 `[S*]` 溯源 + "（截图不清）"保留。

---

## 4. 关键约束与坑（务必遵守）

1. **助手沙箱只能写 `d:\code\crmAndRag`**，不能写 rag 工作区——所以 A1 脚本与本文都暂存在 crmAndRag，**需你手动移到 rag**（§3 的 Move-Item）。你自己的 shell 无此限制。
2. **DashScope key 已在聊天中明文暴露 → 用完请轮换**。任何脚本只从环境变量 `DASHSCOPE_API_KEY` 读，**绝不写进文件/提交**。（rag 目录当前不是 git 仓库，但一旦被纳入版本控制，务必先 .gitignore 掉 .env。）
3. **KB 硬约定**（改 rag-kb 正文时，见 `rag-kb/AGENTS.md`）：
   - 引用具体数字/参数/实验结果必须能追到 `[S*]`/`[W*]`，追不到标 `[X]`，**不得把 `[X]` 说成工作区材料结论**；
   - `…（截图不清）`/`（原文提取不清）`/`【补充】` 等不确定标记**必须原样保留**，不得"补全"；
   - **不编造** benchmark/引用/作者/年份；
   - `_drafts/` 与正文冲突时**以草稿为准**（草稿更接近原始材料）；
   - **`index.json` 是派生物，禁止手改**——改 front matter 后跑 `python rag-kb\build_index.py` 重生成。
4. **原始 PDF/PPTX/PNG 只读**，不要修改工作区根目录的原始材料。
5. 改完必须过既有闸门：`python rag-kb\build_index.py --check` + `python rag-kb\verify_kb.py`（退出码 0）。

---

## 5. 建议执行顺序（每步都可独立验收）

1. **A1 试点 → 全量**（§3）：先把图片型课件的转写自动化，解 pipeline 最大瓶颈。
2. **B4 检索评估集**：建黄金集（问题→应命中文档/段落）+ 命中率脚本，**先有基线**。没有它后面都无法判断是否真变好。
3. **03+14 段级+层次索引**（=B2）：扩 `build_index.py`，把 `##` 段做成可检索单元 + Summary/Chunk 双层（sections[] 已有行号，改造成本低）。
4. **16 混合检索**（=B1）：段落加 embedding，词法+语义双路 + RRF；用 B4 量提升。
5. **08 重排**（=B3）：多路召回后加 rerank；用 B4 量提升。
6. **10 上下文压缩**：大章节召回后压缩重点再喂 LLM；token 下降且 B4 不回退。
7. **06/04/05 增量**（衍生问题集/邻居块/块级加标）→ **02/07/15/09 按需**。
8. **A3 抽取闸门 + A2 渲染保真**：回头提质抽取环节。
9. **C1 索引防漂移**：把两个校验脚本挂 pre-commit/CI。

> 11/12/13/17 暂不做（理由见 §2.2 适用性表）；方案编号↔旧 B 编号映射见 §2.2。

---

## 6. 各项验收标准

| 项 | 验收 |
|---|---|
| A1 | 145 张 PNG 全部转写到 `_extract/vlm/`；抽检对照原图，小字不确定处标了"（截图不清）"；无编造 |
| A3 | extract.py 产出每文件"文本可用/需视觉"判定，与 `sources.md §一` 人工分类一致 |
| B4 | 有黄金集文件 + 命中率/召回评估脚本，跑出**基线数字**并记录 |
| B2 | `index.json` 含段级条目（doc→section→行号/关键词）；路由能直达 `##` 段 |
| B1 | 语义+词法双路召回；对 B4 黄金集，命中率/召回 **≥ 词法基线**（不回退） |
| B3 | 多路候选经 rerank；B4 指标较 B1 再提升或持平 |
| 10 压缩 | 召回段经压缩后喂 LLM；token 用量下降且 B4 指标不回退 |
| C1 | 改动 front matter 后不重建 index 会被 pre-commit/CI 拦下 |
| 全局 | `build_index.py --check` + `verify_kb.py` 退出码 0 |

---

## 7. 与另一个项目（d:\code\crmAndRag）的关系（可选参考）

`d:\code\crmAndRag` 是一个已完成的 **CRM+RAG 融合平台**（Java/Spring Boot），其知识库检索模块（`com.slz.crm.knowledge.retrieval`）已实现本工作区文档里的多项技术：**图文双路 0.7/0.3 融合、BM25 rerank、查询改写、授权过滤**，并有一个可复用的 **RAG 质量评估 harness**（`com.slz.crm.quality`：recall@k/precision/MRR/hitRate/citationPrecision + TTFT/token/失败率 + JSON 报告）。

> 做 **B4（检索评估）** 时，可直接借鉴 `crmAndRag` 的 `RagQualityEvaluator` 设计（黄金集 + 指标 + 可比较报告 + 确定性 fake 本地可跑）——两处评估口径能对齐。
