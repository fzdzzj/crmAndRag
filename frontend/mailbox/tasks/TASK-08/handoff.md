# TASK-08 交接：前端 AI 交互逻辑优化

**状态**：通过（本地实测，见 §4）
**日期**：2026-09-20
**Spec 来源**：主树原本没有 `frontend/mailbox/tasks/TASK-08/spec.md`，已从遗留 worktree
`.qoder/worktrees/agent-general-purpose-83455ce2/frontend/mailbox/tasks/TASK-08/spec.md` 原样复制到本目录。
凡与用户消息冲突处，以用户消息为准，偏差逐条记在 §5。

---

## 1. 交付物

| 文件 | 类型 | 说明 |
|------|------|------|
| `src/utils/retryWithBackoff.ts` | 新增 | 退避算法 + 重试驱动 + 可重试状态判定 |
| `src/utils/error-toast.ts` | 新增 | 技术码→文案映射 + `showErrorToast`（**非 .tsx**，理由见 §5-D1） |
| `src/utils/log.ts` | 修改 | 追加 `trackEvent` / `logErrorFeedback` |
| `src/components/ai/ErrorFeedbackButton.vue` | 新增 | 有用/无用反馈按钮，投一次即收起 |
| `src/hooks/useAiChat.ts` | 修改 | SSE 断线重连 + `Last-Event-ID` 续传 + 错误码上抛 |
| `src/components/ai/AiAssistantDrawer.vue` | 修改 | 错误区换成反馈组件；会话增删失败走统一 toast |
| `src/utils/__tests__/backoff.test.ts` | 新增 | 3 用例（用户验收口径） |
| `src/utils/__tests__/retryWithBackoff.test.ts` | 新增 | 8 用例 |
| `src/utils/__tests__/error-toast.test.ts` | 新增 | 8 用例 |
| `src/utils/__tests__/log.test.ts` | 新增 | 3 用例 |
| `src/utils/__tests__/mockSseStream.ts` | 新增 | 真实 `Response`/`ReadableStream` 测试替身 |
| `src/hooks/__tests__/useAiChat.test.ts` | 新增 | 6 用例（重连/续传/超限/码映射/停止/截断） |
| `src/components/ai/__tests__/ErrorFeedbackButton.test.ts` | 新增 | 3 用例 |
| `e2e/sse-reconnect.spec.ts` | 新增 | 2 用例（Playwright，全打桩，不依赖后端） |
| `docs/test-checkpoints.md` | 修改 | 追加 TASK-08 检查点（前端 AGENTS.md 硬要求） |

## 2. 实现细节

### 2.1 指数退避（`retryWithBackoff.ts`）

```
base = initialDelayMs * multiplier^(attempt-1)      // 1000 * 2^(n-1)
jitter = base * randomFactor * (rand*2 - 1)         // ±10%
delay = min(base + jitter, maxDelayMs)              // 封顶 30s
```
默认配置 `{1000, 30000, 2.0, 0.1}`。驱动 `retryWithBackoff(task, opts)` 可注入
`sleep` / `signal` / `isRetryable` / `onRetry`，`sleep` 默认走可被 abort 打断的 `setTimeout`。
重试语义为 **1 次首发 + 最多 5 次重试**，重试用尽原样抛出最后一个错误（不吞栈）。

### 2.2 SSE 重连（`useAiChat.ts`）

- `parseSSEStream` 之前丢弃 `id:` 行；现在解析为 `SseEvent.id` 并跨重连保留 `lastEventId`。
- 重连请求带 `Last-Event-ID: generationId:sequence` 头。后端
  `AiChatSseEventWriter#resume` → `AiSseEventBuffer#eventsAfter` 是**排他重放**，
  所以续传不会重复已收到的文本（单测按此契约构造桩流）。
- 终态判定：`done` / `error` / `cancelled` / `stopped` 之外流就结束 = 静默截断，
  按 `NETWORK_ERROR` 抛出以触发重连。
- 可重试集合：`408 425 429 500 502 503 504` + 网络层 `TypeError` + 截断；
  其余 4xx 与流内 `error` 事件**不重试**（服务端已落库，重试会重复生成）。
- 重试期 Toast：`message.warning({ content: '网络连接不稳定，正在重试... (n/5)', key: 'ai-retry' })`，
  成功后 `message.destroy('ai-retry')`。
- 用户点“停止”产生的 `AbortError` 不重试、不弹错，仅把消息标记为 `interrupted`。
- 新增导出 `errorCode`（computed）：`error` 给用户看，`errorCode` 给埋点用。

### 2.3 错误提示层（`error-toast.ts`）

映射表按**后端真相**建立，而非 spec 里的假设：
- SSE 字符串码（`AiChatServiceImpl#sendError` 实际发出的 7 个）：
  `PARAM_INVALID / RATE_LIMITED / SESSION_ARCHIVED / DEPENDENCY_UNAVAILABLE / LLM_ERROR / UNAUTHORIZED / RESUME_UNAVAILABLE`
- 数字码 `93001-93004`（`ErrorCode.java` AI 段，语义见 §5-D3）
- 网络哨兵 `NETWORK_ERROR`

三级退化：已登记码 → 映射文案；未登记码 → 服务端 `msg`；都没有 → 码本身；
无码无 msg → `GENERIC_AI_ERROR_MESSAGE`。任何路径都不会显示空白，也不会把裸 `93001` 直接丢给用户。
`HTTP !ok` 时先解析响应体 JSON 取 `code`/`msg`，取不到才退化为 `HTTP_<status>`。
所有 toast 复用 `key: 'ai-error'`，重试期间不堆叠。

### 2.4 反馈路径

`ErrorFeedbackButton.vue` 渲染友好文案 + 有用/无用；点击后
`logErrorFeedback(code, helpful)` → `trackEvent('ai_error_feedback', { code, helpful })`，
按钮收起为“感谢反馈”，一次错误只能投一票。
**当前后端没有埋点接收端点**（`grep` openapi.yaml 与 Java 源码均无 track/feedback），
所以 `trackEvent` 落 `console.info('[track] ...')` 并留了替换点，未擅自新增依赖或外发请求。

## 3. 验收截图

| 文件 | 内容 |
|------|------|
| `evidence-1-retry-toast.png` | 断线后顶部 Toast「网络连接不稳定，正在重试... (1/5)」+ 抽屉停止按钮 |
| `evidence-2-resumed-content.png` | 续传后内容补全 |
| `evidence-3-error-feedback-buttons.png` | `RATE_LIMITED` → 红字「操作过于频繁，请稍后再试」+ 有用/无用 |
| `evidence-4-thanks.png` | 投票后「感谢反馈」 |

## 4. 验收输出（现场实跑，非转述）

```
$ pnpm exec vitest run src/utils/__tests__/backoff.test.ts
 Test Files  1 passed (1)
      Tests  3 passed (3)

$ pnpm exec vitest run src/utils/__tests__/error-toast.test.ts
      Tests  8 passed (8)

$ pnpm exec playwright test e2e/sse-reconnect.spec.ts --reporter=list
  ok 1 [chromium] › e2e\sse-reconnect.spec.ts:42:1 › 断线重连：截断流触发退避重试提示并续传补全内容 (2.3s)
  ok 2 [chromium] › e2e\sse-reconnect.spec.ts:64:1 › 流内 error 事件按技术码出友好文案并提供有用/无用反馈 (1.1s)
  2 passed (4.9s)

$ pnpm exec vitest run          # 全量回归
 Test Files  15 passed (15)
      Tests  150 passed (150)

$ pnpm lint:check
 ✖ 22 problems (0 errors, 22 warnings)   # 22 条 warning 全在未改动文件，exit 0

$ pnpm type-check:check
 # exit 2，3 个 error 全在本任务未触碰的文件，见 §6-3 的归属验证

$ curl -sS -m 5 http://localhost:8080/api/ai/chat
curl: (7) Failed to connect to localhost port 8080: Connection refused
```
完整输出见同目录 `verify.log`。

## 5. 与 spec / 用户指令的偏差

- **D1 文件名 `error-toast.ts` 而非 `.tsx`**：项目没装 `@vitejs/plugin-vue-jsx`，
  `.tsx` 里写 JSX 编译不过；富文本提示用 Vue 的 `h()` 即可。新增依赖需用户授权，故选可逆方案。
- **D2 测试文件名**：用户验收命令是 `backoff.test.ts`（3 用例）与 `e2e/sse-reconnect.spec.ts`（2 用例），
  spec 写的是 `retryWithBackoff.test.ts` / `ai-chat-reconnect.spec.ts`。**按用户命令命名**，
  spec 那个名字留给重试驱动的测试，两者不重复。
- **D3 `93001` 文案与 spec 不一致**：spec 说 `93001 = AI 服务暂时不可用`，
  后端 `ErrorCode.java` 实为 `AI_ACTION_TIMEOUT(93001, "确认已超时，请重新发起")`。
  按后端真相映射为「AI 操作确认已超时，请重新发起确认」。spec 的假设需要 owner 更正。
- **D4 `pnpm test` 不存在**：前端脚本里没有 `test`（见 `package.json`），
  回归口径按 `frontend/AGENTS.md` 用 `pnpm exec vitest run`。
- **D5 验收命令 3（curl 8080）无法执行**：本地后端未启动（上面有 curl 原文）。
  等价证据改为：单测断言 `HTTP 503 + {"code":"LLM_ERROR"}` 只会产出映射文案，
  E2E 断言错误区 `not.toContainText('RATE_LIMITED')`。

## 6. 现场发现，需主 agent 裁定

1. **`useAiChat` 请求路径可能本来就 404**：`STREAM_URL = '/ai/chat/stream'`，
   而 `vite.config.ts` 只代理 `/api`、`nginx.conf` 只转发 `/api/`。
   直连时该请求打不到后端 `/ai/chat/stream`。属既有缺陷，改动会影响部署链路，本次**未动**，
   已写进 `docs/test-checkpoints.md` 的待拍板项。
2. **与 TASK-09 的产物重叠**：并行的 TASK-09 在本树新增了
   `src/constants/error-code-map.ts`（全量 REST 错误码→文案，接在 axios 响应拦截器上）。
   与本任务的 `error-toast.ts`（AI/SSE 码→toast 文案）职责相邻。两者当前互不依赖、可独立合并，
   但**是否合并为一层需 owner 定夺**，否则将来会出现两套映射表漂移。
3. **`pnpm type-check:check` 在本树是红的，但红点不属于本任务**：
   结案前实测剩 3 个 error，全在 `CreateActivityModal.vue` / `FileUploadModal.vue` 的
   `assistUserIds` 上（`BusinessActivityDto`、`StageApprovalForm` 里没这个字段）。
   已验证为 **master 既有欠账**：`git grep -c assistUserIds HEAD -- frontend/src` 只在三个组件文件里命中，
   `frontend/src/api/` 生成类型里 0 命中，且 `src/api/axios/` 工作区未被改动。
   本任务 12:29 单独跑 `type-check:check` 是 exit 0（当时我的生产代码已全部就位），
   中途一度涨到 9 个 error，多出的 6 个是 `{ file: File }` 类型退化，随 TASK-09 改
   `src/api/types.ts` / `apiClient.ts` 后消失——**并发 lane 的在途状态，不是本任务引入**。
   结论：TASK-08 贡献 0 个类型错误（vue-tsc 全项目报错清单里没有本任务任何文件），
   但结案门禁要不要放行这 3 个既有 error，请 owner 拍板。
4. **`tests/useAiChat.test.ts` 是占位假测试**（含 `expect(true).toBe(true)`），
   与新增的 `src/hooks/__tests__/useAiChat.test.ts` 同模块重名。本次未删（避免动别人产物），
   建议后续任务合并掉。

## 7. 复跑方式

```bash
cd frontend
pnpm exec vitest run                                   # 150 passed
pnpm exec vitest run src/utils/__tests__/backoff.test.ts
pnpm exec playwright test e2e/sse-reconnect.spec.ts    # 2 passed（自起 pnpm dev，无需后端）
pnpm lint:check && pnpm type-check:check               # 见 §6-3 关于 type-check 的归属说明
```
E2E 用 `page.route` 打桩，按 pathname 精确匹配 `/api/` 前缀；
**不要写成 glob `**/api/**`**，那会连 `/src/api/*.ts` 的模块请求一起截走，页面直接白屏。
