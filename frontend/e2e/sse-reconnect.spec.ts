import { test, expect, type Page } from '@playwright/test';

/**
 * SSE 断线重连 / 错误提示 E2E。
 * 全部后端交互用 page.route 打桩，不需要真实服务：
 * 截断流（无 done 事件）触发客户端重连，error 事件触发友好文案 + 反馈按钮。
 */

const sseFrame = (event: string, data: unknown, id?: string) =>
  `${id ? `id:${id}\n` : ''}event:${event}\ndata:${JSON.stringify(data)}\n\n`;

const sseHeaders = { 'Content-Type': 'text/event-stream' };
const jsonHeaders = { 'Content-Type': 'application/json' };

// 只截 XHR 走的 /api 前缀。用 glob 通配（api 前后各加双星）会连 /src/api 下的模块请求一起截走，页面白屏。
const isApiCall = (url: URL) => url.pathname.startsWith('/api/');
const isStreamCall = (url: URL) => url.pathname === '/ai/chat/stream';

async function loginAndOpenAssistant(page: Page) {
  await page.addInitScript(() => {
    const b64url = (obj: unknown) =>
      btoa(JSON.stringify(obj)).replace(/=+$/, '').replace(/\+/g, '-').replace(/\//g, '_');
    const payload = { roleID: 1, userID: 1, exp: Math.floor(Date.now() / 1000) + 86400 };
    localStorage.setItem('token', `${b64url({ alg: 'HS256' })}.${b64url(payload)}.sig`);
    localStorage.setItem('payload', JSON.stringify(payload));
    // 首次登录会弹"使用教程"抽屉盖住 AI 按钮，标记为已看过
    localStorage.setItem('tour.intro.seen.1', '1');
  });

  await page.route(isApiCall, (route) =>
    route.fulfill({
      headers: jsonHeaders,
      body: JSON.stringify({ code: 1, msg: 'ok', data: [] }),
    }),
  );

  await page.goto('/#/');
  await page.getByRole('button', { name: /AI\s*助手/ }).click();
  await expect(page.getByPlaceholder('输入消息，Enter 发送')).toBeVisible();
}

test('断线重连：截断流触发退避重试提示并续传补全内容', async ({ page }) => {
  await loginAndOpenAssistant(page);

  let attempts = 0;
  await page.route(isStreamCall, (route) => {
    attempts += 1;
    const body =
      attempts === 1
        ? sseFrame('start', { sessionId: '11', generationId: 'g1' }, 'g1:1') +
          sseFrame('delta', { content: '网络' }, 'g1:2')
        : sseFrame('delta', { content: '抖动了' }, 'g1:3') + sseFrame('done', { sessionId: '11' }, 'g1:4');
    return route.fulfill({ headers: sseHeaders, body });
  });

  await page.getByPlaceholder('输入消息，Enter 发送').fill('测试重连');
  await page.keyboard.press('Enter');

  await expect(page.getByText('网络连接不稳定，正在重试... (1/5)')).toBeVisible({ timeout: 10_000 });
  await expect(page.getByText('网络抖动了')).toBeVisible({ timeout: 15_000 });
  expect(attempts).toBe(2);
});

test('流内 error 事件按技术码出友好文案并提供有用/无用反馈', async ({ page }) => {
  await loginAndOpenAssistant(page);

  await page.route(isStreamCall, (route) =>
    route.fulfill({
      headers: sseHeaders,
      body:
        sseFrame('start', { sessionId: '11', generationId: 'g1' }, 'g1:1') +
        sseFrame('error', { code: 'RATE_LIMITED', msg: '操作过于频繁，请稍后再试' }, 'g1:2'),
    }),
  );

  await page.getByPlaceholder('输入消息，Enter 发送').fill('触发限流');
  await page.keyboard.press('Enter');

  const feedback = page.locator('.ai-error-feedback');
  await expect(feedback).toContainText('操作过于频繁，请稍后再试', { timeout: 10_000 });
  await expect(feedback).not.toContainText('RATE_LIMITED');
  // antd Button 会在两个中文字之间插空格，可访问名是"有 用"，故用 \s* 容忍
  const helpful = page.getByRole('button', { name: /有\s*用/ });
  const unhelpful = page.getByRole('button', { name: /无\s*用/ });
  await expect(helpful).toBeVisible();
  await expect(unhelpful).toBeVisible();

  await helpful.click();
  await expect(feedback).toContainText('感谢反馈');
  await expect(helpful).toHaveCount(0);
});
