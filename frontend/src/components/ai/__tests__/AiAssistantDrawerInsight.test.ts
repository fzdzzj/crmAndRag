// @vitest-environment jsdom
// 宿主 2 接线：AiAssistantDrawer 与「📊 诊断客户健康度」快捷指令联动展示 CustomerInsightPanel
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { createApp, h, nextTick, ref, type ComponentPublicInstance } from 'vue';

const sessionRefetch = vi.hoisted(() => ({ fn: undefined as unknown as ReturnType<typeof vi.fn> }));
const toast = vi.hoisted(() => ({ success: vi.fn(), info: vi.fn(), error: vi.fn(), warning: vi.fn() }));

vi.mock('vue-router', () => ({
  useRoute: () => ({ query: { 'filters.id': '301' } }),
  useRouter: () => ({ push: vi.fn() }),
}));

vi.mock('@/hooks/useAiChat', () => ({
  useAiChat: () => ({
    messages: ref([]),
    isStreaming: ref(false),
    error: ref(null),
    errorCode: ref(null),
    sendMessage: vi.fn(),
    loadHistory: vi.fn(),
    reset: vi.fn(),
    stop: vi.fn(),
  }),
}));

vi.mock('@/hooks/useAiSession', () => ({
  useAiSessions: () => ({ sessions: ref([]), refetch: vi.fn() }),
  useCreateAiSession: () => ({ mutateAsync: vi.fn() }),
  useDeleteAiSession: () => ({ mutateAsync: vi.fn() }),
  useAiSessionMessages: () => {
    sessionRefetch.fn = vi.fn();
    return { data: ref([]), refetch: sessionRefetch.fn };
  },
}));

vi.mock('@/hooks/useAiAction', () => ({
  useAiAction: () => ({ loading: ref(false), confirm: vi.fn(), cancelAction: vi.fn(), edit: vi.fn() }),
}));

vi.mock('@/components/ai/ErrorFeedbackButton.vue', async () => {
  const { defineComponent } = await import('vue');
  return { default: defineComponent({ name: 'ErrorFeedbackButtonStub', setup: () => () => null }) };
});

vi.mock('@/components/ai/KnowledgeDocumentDrawer.vue', async () => {
  const { defineComponent } = await import('vue');
  return { default: defineComponent({ name: 'KnowledgeDocumentDrawerStub', setup: () => () => null }) };
});

vi.mock('@/components/ai/CustomerInsightPanel.vue', async () => {
  const { h: hyp, defineComponent } = await import('vue');
  return {
    default: defineComponent({
      name: 'CustomerInsightPanelStub',
      props: { companyId: { type: Number, required: true } },
      emits: ['created'],
      setup: (props, { emit }) => () =>
        hyp('div', { class: 'insight-stub', 'data-company': String(props.companyId) }, [
          hyp('button', { class: 'convert', onClick: () => emit('created', 'PD-1') }, 'convert'),
        ]),
    }),
  };
});

vi.mock('ant-design-vue', async () => {
  const { h: hyp, defineComponent } = await import('vue');
  const Drawer = defineComponent({
    name: 'Drawer',
    props: { open: { type: Boolean, default: false } },
    emits: ['update:open', 'close'],
    setup: (_props, { slots }) => () => hyp('div', { class: 'drawer' }, slots.default?.()),
  });
  const Button = defineComponent({
    name: 'Button',
    emits: ['click'],
    setup: (_props, { slots, emit }) => () =>
      hyp('button', { onClick: (e: Event) => emit('click', e) }, slots.default?.()),
  });
  const Input = defineComponent({ name: 'Input', setup: () => () => hyp('input') });
  const Tag = defineComponent({ name: 'Tag', setup: (_p, { slots }) => () => hyp('span', slots.default?.()) });
  return { Drawer, Button, Input, Tag, message: toast };
});

import AiAssistantDrawer from '@/components/ai/AiAssistantDrawer.vue';

function mountDrawer() {
  const host = document.createElement('div');
  document.body.appendChild(host);
  const app = createApp({ render: () => h(AiAssistantDrawer, { open: true }) });
  const vm = app.mount(host) as ComponentPublicInstance;
  return { host, vm, unmount: () => app.unmount() };
}

describe('AiAssistantDrawer 客户诊断宿主', () => {
  beforeEach(() => {
    toast.success.mockClear();
    sessionRefetch.fn?.mockClear();
  });

  it('客户上下文明确（路由 filters.id）时展示 Panel 并透传 companyId', () => {
    const { host, unmount } = mountDrawer();
    const stub = host.querySelector('.insight-stub');
    expect(stub).not.toBeNull();
    expect(stub?.getAttribute('data-company')).toBe('301');
    unmount();
  });

  it('Panel 的 created 事件给可见反馈并刷新会话', async () => {
    const { host, unmount } = mountDrawer();
    host.querySelector<HTMLButtonElement>('.convert')?.dispatchEvent(new MouseEvent('click', { bubbles: true }));
    await nextTick();
    expect(toast.success).toHaveBeenCalledWith(expect.stringContaining('PD-1'));
    expect(sessionRefetch.fn).toHaveBeenCalled();
    unmount();
  });
});
