// @vitest-environment jsdom
// 宿主 1 接线：客户公司详情弹窗（CompanyEditModal 只读视图）内嵌 CustomerInsightPanel
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { createApp, nextTick, ref, type ComponentPublicInstance } from 'vue';

const toast = vi.hoisted(() => ({ success: vi.fn(), error: vi.fn(), warning: vi.fn(), info: vi.fn() }));

vi.mock('@/hooks/useCompany.ts', () => ({
  useUpdateCompany: () => ({ mutate: vi.fn(), isPending: ref(false) }),
  useCompanyContacts: () => ({ data: ref([]), isLoading: ref(false), refetch: vi.fn(() => Promise.resolve()) }),
  useGroupDeptMaster: () => ({
    groupOptions: ref([]),
    deptOptions: ref([]),
    selectedGroupId: ref(undefined),
    isSearchingGroup: ref(false),
    isSearchingDept: ref(false),
    isCreatingGroup: ref(false),
    isCreatingDept: ref(false),
    pendingGroupName: ref(undefined),
    pendingDeptName: ref(undefined),
    groupCreateOption: ref(undefined),
    deptCreateOption: ref(undefined),
    groupSearchText: ref(''),
    deptSearchText: ref(''),
    searchGroups: vi.fn(),
    searchDepts: vi.fn(),
    selectGroup: vi.fn(),
    markPendingGroup: vi.fn(),
    markPendingDept: vi.fn(),
    clearPending: vi.fn(),
    commitPending: vi.fn(() => Promise.resolve(true)),
    syncGroupByName: vi.fn(),
  }),
}));

vi.mock('@/components/client/CustomerModal.vue', async () => {
  const { defineComponent } = await import('vue');
  return { default: defineComponent({ name: 'CustomerModalStub', setup: () => () => null }) };
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
  const passthrough = (name: string) =>
    defineComponent({ name, setup: (_p, { slots }) => () => hyp('div', slots.default?.()) });
  const Modal = Object.assign(passthrough('Modal'), { confirm: vi.fn() });
  return {
    Modal,
    Input: passthrough('Input'),
    Form: passthrough('Form'),
    FormItem: passthrough('FormItem'),
    Divider: defineComponent({ name: 'Divider', setup: (_p, { slots }) => () => hyp('div', { class: 'divider' }, slots.default?.()) }),
    Table: passthrough('Table'),
    Spin: passthrough('Spin'),
    Select: passthrough('Select'),
    Rate: passthrough('Rate'),
    message: toast,
  };
});

import CompanyEditModal from '@/components/client/CompanyEditModal.vue';

const company = {
  id: 7,
  companyName: '示例客户',
  industry: 'IT',
  customerType: '直销',
  belongGroup: '示例集团',
  dept: '一部',
  address: '地址',
  description: '简介',
  grade: 3,
  creatorName: 'c',
  ownerName: 'o',
  createTime: '',
  updateTime: '',
  isDeleted: false,
  creatorId: 1,
  ownerId: 2,
};

function mountModal() {
  const host = document.createElement('div');
  document.body.appendChild(host);
  const app = createApp(CompanyEditModal);
  // 模板内 a-select-option 未在 ant-design-vue 桩里导出；按 PascalCase 注册同名桩，避免组件解析告警
  app.component('ASelectOption', { setup: () => () => null });
  const vm = app.mount(host) as ComponentPublicInstance & { openView: (c: typeof company) => void };
  return { host, vm, unmount: () => app.unmount() };
}

describe('CompanyEditModal 客户诊断宿主', () => {
  beforeEach(() => {
    toast.success.mockClear();
  });

  it('只读查看客户时展示 Panel 并透传 companyId', async () => {
    const { host, vm, unmount } = mountModal();
    vm.openView(company);
    await nextTick();
    const stub = host.querySelector('.insight-stub');
    expect(stub).not.toBeNull();
    expect(stub?.getAttribute('data-company')).toBe('7');
    unmount();
  });

  it('Panel 的 created 事件给可见反馈并刷新（重挂 Panel）', async () => {
    const { host, vm, unmount } = mountModal();
    vm.openView(company);
    await nextTick();
    host.querySelector<HTMLButtonElement>('.convert')?.dispatchEvent(new MouseEvent('click', { bubbles: true }));
    await nextTick();
    expect(toast.success).toHaveBeenCalledWith(expect.stringContaining('PD-1'));
    // 刷新：重挂后 Panel 仍在且 companyId 不变
    expect(host.querySelector('.insight-stub')?.getAttribute('data-company')).toBe('7');
    unmount();
  });
});
