// @vitest-environment jsdom
// 反馈按钮：一次错误一次投票，点击即埋点
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { createApp, h, nextTick, type ComponentPublicInstance } from 'vue';

vi.mock('@/utils/log', () => ({ logErrorFeedback: vi.fn() }));
vi.mock('ant-design-vue', async () => {
  const { h: hyp, defineComponent } = await import('vue');
  const Button = defineComponent({
    name: 'Button',
    props: { disabled: { type: Boolean, default: false }, size: { type: String, default: '' } },
    emits: ['click'],
    setup: (props, { slots, emit }) => () =>
      hyp('button', { disabled: props.disabled, onClick: (e: Event) => emit('click', e) }, slots.default?.()),
  });
  return { Button };
});

import ErrorFeedbackButton from '../ErrorFeedbackButton.vue';
import { logErrorFeedback } from '@/utils/log';

function mountButton(props: Record<string, unknown>) {
  const host = document.createElement('div');
  document.body.appendChild(host);
  const app = createApp({ render: () => h(ErrorFeedbackButton, props) });
  const vm = app.mount(host) as ComponentPublicInstance;
  return { host, vm, unmount: () => app.unmount() };
}

const report = vi.mocked(logErrorFeedback);

describe('ErrorFeedbackButton', () => {
  beforeEach(() => {
    report.mockClear();
  });

  it('渲染友好文案与两个反馈按钮', () => {
    const { host, unmount } = mountButton({ code: 'LLM_ERROR', message: '模型暂时不可用，请稍后重试' });
    const buttons = host.querySelectorAll('button');
    expect(buttons).toHaveLength(2);
    expect(host.textContent).toContain('模型暂时不可用');
    expect(host.textContent).not.toContain('LLM_ERROR');
    unmount();
  });

  it('点击“有用”上报 helpful=true，点击“无用”上报 helpful=false', async () => {
    const first = mountButton({ code: '93001', message: 'AI 操作确认已超时' });
    first.host.querySelectorAll('button')[0].dispatchEvent(new MouseEvent('click', { bubbles: true }));
    await nextTick();
    expect(report).toHaveBeenCalledWith('93001', true);
    first.unmount();

    const second = mountButton({ code: '93001', message: 'AI 操作确认已超时' });
    second.host.querySelectorAll('button')[1].dispatchEvent(new MouseEvent('click', { bubbles: true }));
    await nextTick();
    expect(report).toHaveBeenCalledWith('93001', false);
    second.unmount();
  });

  it('投票后收起按钮，避免重复上报', async () => {
    const { host, unmount } = mountButton({ code: 'NETWORK_ERROR', message: '网络连接中断' });
    host.querySelectorAll('button')[0].dispatchEvent(new MouseEvent('click', { bubbles: true }));
    await nextTick();
    expect(host.querySelectorAll('button')).toHaveLength(0);
    expect(host.textContent).toContain('感谢反馈');
    unmount();
  });
});
