# TASK-08: 前端 AI 交互逻辑优化

## 1. 目标

提升 SSE 流式对话的容错性与用户体验：实现指数退避重连、技术码→友好文案映射、用户反馈路径。

## 2. 现状证据（evidence）

| 文件 | 问题 |
|------|------|
| `src/hooks/useAiChat.ts` (L173-176) | HTTP 错误直接抛出，无重试；断线后会话不可恢复 |
| `src/components/ai/AiAssistantDrawer.vue` (L65) | `chat.error` 仅显示原始技术码（如 93001），无操作建议 |
| `src/utils/` | 无通用 error-toast 封装，错误提示散落在各组件 |
| 无埋点 | 无法收集用户对错误处理的满意度 |

## 3. 改动契约（contract）

### 3.1 指数退避重连策略

**算法**（`src/utils/retryWithBackoff.ts`）：
```typescript
interface BackoffConfig {
  initialDelayMs: number;   // 1000
  maxDelayMs: number;       // 30000
  multiplier: number;       // 2.0
  randomFactor: number;     // 0.1 (±10%)
}
function calculateDelay(attempt: number, cfg: BackoffConfig): number {
  const base = cfg.initialDelayMs * Math.pow(cfg.multiplier, attempt - 1);
  const jitter = base * cfg.randomFactor * (Math.random() * 2 - 1);
  return Math.min(base + jitter, cfg.maxDelayMs);
}
```

**集成到 `useAiChat.ts`**：
- 网络异常（`TypeError: Failed to fetch` / `DOMException: aborted`）触发重连
- 最多重试 5 次，失败后统一调用 `showErrorToast(error)`
- 重连时显示 Toast："网络连接不稳定，正在重试... (2/5)"

### 3.2 错误提示层封装

**文件**：`src/utils/error-toast.tsx`
```tsx
export function showErrorToast(techCode: string | Error): void {
  const map: Record<string, string> = {
    '93001': 'AI 服务暂时不可用，请稍后重试或联系管理员',
    '93002': '知识库检索超时，请检查网络后重试',
    'NETWORK_ERROR': '网络连接中断，请检查您的网络设置',
  };
  const message = typeof techCode === 'string' ? map[techCode] || techCode : techCode.message;
  antdMessage.error({ content: message, key: 'ai-error' });
}
```

**调用点**：
- `useAiChat.ts` catch block (L258-264)
- `AiAssistantDrawer.vue` session create/delete failure (L141-143, L160-162)

### 3.3 用户反馈路径

**组件**：`src/components/ai/ErrorFeedbackButton.vue`
```vue
<template>
  <div class="error-feedback">
    <span>{{ errorMsg }}</span>
    <Button size="small" @click="report(true)">有用</Button>
    <Button size="small" @click="report(false)">无用</Button>
  </div>
</template>
```

**埋点**（`src/utils/log.ts` 新增）：
```typescript
export function logErrorFeedback(errorCode: string, helpful: boolean): void {
  trackEvent('ai_error_feedback', { code: errorCode, helpful });
}
```

**UI 位置**：`AiAssistantDrawer.vue` L65 下方，仅当 `chat.error` 非空时渲染。

## 4. 门禁（gates）

| 类型 | 命令 | 预期 |
|------|------|------|
| 单元测试 | `pnpm exec vitest run src/utils/__tests__/retryWithBackoff.test.ts` | 退避算法覆盖率≥90% |
| E2E | `pnpm exec playwright test e2e/ai-chat-reconnect.spec.ts` | 断言 Toast 文案与反馈按钮可见性 |
| 类型检查 | `pnpm type-check:check` | 无新增 TS 错误 |
| Lint | `pnpm lint:check` | 无 ESLint 警告 |

**禁改范围**：
- ❌ backend Java 代码
- ❌ `src/main/resources/db/migration/*.sql`
- ❌ `application*.yml`（仅允许加注释）

## 5. 测试计划（test plan）

### 5.1 单元测试

**文件**：`src/utils/__tests__/retryWithBackoff.test.ts`
```typescript
describe('calculateDelay', () => {
  it('attempt 1 → ~1000ms (±10%)', () => { ... });
  it('attempt 5 → ≤30000ms', () => { ... });
  it('jitter range validation', () => { ... });
});
```

**文件**：`src/utils/__tests__/error-toast.test.tsx`
```typescript
it('maps 93001 → user-friendly text', () => { ... });
it('falls back to raw code when unknown', () => { ... });
```

**SSE 流模拟**（`src/utils/__tests__/mockSseStream.ts`）：
- 正常流：连续 delta 事件
- 网络抖动：第 3 个 chunk 后断开，验证重连逻辑
- 服务端错误：返回 500，验证错误提示

### 5.2 E2E 测试

**文件**：`e2e/ai-chat-reconnect.spec.ts`
```typescript
test('AI chat reconnect with exponential backoff', async ({ page }) => {
  await page.goto('/ai-assistant');
  await page.fill('input[placeholder*="输入消息"]', '测试重连');
  await page.press('input', 'Enter');
  
  // 断言 Toast 出现
  await expect(page.locator('.ant-message')).toContainText('正在重试');
  
  // 断言错误提示文案
  await expect(page.locator('.error')).toContainText('AI 服务暂时不可用');
  
  // 断言反馈按钮
  await expect(page.locator('.error-feedback Button')).toHaveCount(2);
});
```

## 6. 验收命令与预期输出（acceptance criteria）

```bash
# 1. 退避算法单测
$ pnpm exec vitest run src/utils/__tests__/retryWithBackoff.test.ts
✓ calculateDelay: attempt 1 → 1000ms ±10%
✓ calculateDelay: attempt 5 → capped at 30000ms

# 2. 错误提示单测
$ pnpm exec vitest run src/utils/__tests__/error-toast.test.tsx
✓ maps 93001 → "AI 服务暂时不可用..."

# 3. E2E 重连场景
$ pnpm exec playwright test e2e/ai-chat-reconnect.spec.ts
✓ [ai-chat-reconnect] AI chat reconnect with exponential backoff (3.2s)

# 4. 全量类型检查
$ pnpm type-check:check
No errors

# 5. Lint 检查
$ pnpm lint:check
ESLint passed
```

## 7. 交付物清单（deliverables）

| 文件 | 状态 |
|------|------|
| `src/utils/retryWithBackoff.ts` | ✏️ 新增 |
| `src/utils/error-toast.tsx` | ✏️ 新增 |
| `src/components/ai/ErrorFeedbackButton.vue` | ✏️ 新增 |
| `src/utils/__tests__/retryWithBackoff.test.ts` | ✏️ 新增 |
| `src/utils/__tests__/error-toast.test.tsx` | ✏️ 新增 |
| `src/utils/__tests__/mockSseStream.ts` | ✏️ 新增 |
| `e2e/ai-chat-reconnect.spec.ts` | ✏️ 新增 |
| `src/hooks/useAiChat.ts` | ✏️ 修改（集成重连 + 错误提示） |
| `src/components/ai/AiAssistantDrawer.vue` | ✏️ 修改（嵌入反馈按钮） |

---

**Spec 版本**: v1.0  
**Owner**: frontend-team  
**Deadline**: W42
