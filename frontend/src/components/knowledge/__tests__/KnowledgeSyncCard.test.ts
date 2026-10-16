// @vitest-environment jsdom
// 向量运维卡片（unify-vector-ops-endpoints 前端任务 2.1）：五实体入口 + 最近同步结果渲染 + 事件上抛
import { describe, it, expect, vi, type Mock } from 'vitest';
import { createApp, h } from 'vue';

vi.mock('@/hooks/useKnowledge', () => ({
  SYNC_ENTITIES: [
    { key: 'customer', label: '客户' },
    { key: 'contract', label: '合同' },
    { key: 'contactTask', label: '跟进任务' },
    { key: 'businessActivity', label: '商业活动' },
    { key: 'salesOpportunity', label: '销售商机' },
  ],
}));

import KnowledgeSyncCard from '../KnowledgeSyncCard.vue';

function mountCard(
  props: Record<string, unknown> = {},
  events: Record<string, Mock> = {},
) {
  const host = document.createElement('div');
  document.body.appendChild(host);
  const app = createApp({
    render: () => h(KnowledgeSyncCard, { ...props, ...events }),
  });
  const vm = app.mount(host);
  return { host, vm, unmount: () => app.unmount() };
}

describe('KnowledgeSyncCard', () => {
  it('渲染五实体入口按钮 + 全部同步按钮', () => {
    const { host, unmount } = mountCard();
    const buttons = Array.from(host.querySelectorAll('button')).map((b) =>
      b.textContent?.trim(),
    );
    expect(buttons).toEqual([
      '客户',
      '合同',
      '跟进任务',
      '商业活动',
      '销售商机',
      '全部同步',
    ]);
    unmount();
  });

  it('无同步记录时展示空态文案', () => {
    const { host, unmount } = mountCard();
    expect(host.textContent).toContain('尚无同步记录');
    unmount();
  });

  it('有同步结果时按实体渲染 成功/失败/总计 计数', () => {
    const { host, unmount } = mountCard({
      results: {
        customer: { total: 10, success: 9, failure: 1 },
        contract: { total: 20, success: 18, failure: 2 },
      },
    });
    const text = host.textContent ?? '';
    expect(text).toContain('最近同步结果');
    expect(text).toContain('客户');
    expect(text).toContain('成功 9 / 失败 1 / 总计 10');
    expect(text).toContain('成功 18 / 失败 2 / 总计 20');
    expect(text).toContain('未同步'); // 未同步实体显示占位
    unmount();
  });

  it('点击实体按钮上抛 sync 事件（携带实体 key）', () => {
    const onSync = vi.fn();
    const { host, unmount } = mountCard({}, { onSync });
    const buttons = Array.from(host.querySelectorAll('button'));
    buttons[0].dispatchEvent(new MouseEvent('click'));
    expect(onSync).toHaveBeenCalledWith('customer');
    unmount();
  });

  it('点击全部同步上抛 syncAll 事件', () => {
    const onSyncAll = vi.fn();
    const { host, unmount } = mountCard({}, { onSyncAll });
    const buttons = Array.from(host.querySelectorAll('button'));
    buttons[5].dispatchEvent(new MouseEvent('click'));
    expect(onSyncAll).toHaveBeenCalled();
    unmount();
  });
});
