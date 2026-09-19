// @vitest-environment jsdom
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { useTutorial } from '@/hooks/useTutorial';
import { tutorials } from '@/constants/tour';
import { TutorialStorage } from '@/utils/tutorialStorage';

type DriverTestConfig = {
  onCloseClick?: () => void;
  onNextClick?: () => void;
  onDestroyed?: () => void;
};

type PopoverRenderOptions = {
  wrapper: HTMLElement;
  footer: HTMLElement;
  description: HTMLElement;
  closeButton: HTMLButtonElement;
};

type ConfirmModalOptions = {
  onOk?: () => void;
  onCancel?: () => void;
};

type SuccessModalOptions = {
  content: string;
};

const routerState = vi.hoisted(() => ({
  currentRoute: { value: { path: '/client/list' } },
  push: vi.fn(),
  afterEach: vi.fn(),
}));

const tutorialMock = vi.hoisted(() => ({
  enable: vi.fn(),
  disable: vi.fn(),
}));
const mockTutorialEnable = tutorialMock.enable;
const mockTutorialDisable = tutorialMock.disable;
const mockWarning = vi.hoisted(() => vi.fn());

const driverInstances: {
  config: DriverTestConfig;
  instance: {
    highlight: ReturnType<typeof vi.fn>;
    destroy: ReturnType<typeof vi.fn>;
  };
}[] = [];

const modalState = vi.hoisted(() => ({
  confirms: [] as ConfirmModalOptions[],
  successes: [] as SuccessModalOptions[],
}));

vi.mock('vue-router', () => ({
  useRouter: () => routerState,
}));

vi.mock('driver.js', () => ({
  driver: vi.fn((config: DriverTestConfig) => {
    const highlight = vi.fn();
    const destroy = vi.fn(() => {
      config.onDestroyed?.();
    });
    const instance = { highlight, destroy };
    driverInstances.push({ config, instance });
    return instance;
  }),
}));

vi.mock('ant-design-vue', () => ({
  Modal: {
    confirm: vi.fn((options: ConfirmModalOptions) => {
      modalState.confirms.push(options);
      return { destroy: vi.fn() };
    }),
    success: vi.fn((options: SuccessModalOptions) => {
      modalState.successes.push(options);
      return { destroy: vi.fn() };
    }),
  },
  message: {
    warning: mockWarning,
  },
}));

vi.mock('@/utils/tutorialMock', () => ({
  TutorialMock: tutorialMock,
}));

function createTestAnchor(anchor: string) {
  const element = document.createElement('button');
  element.dataset.tour = anchor;
  element.getClientRects = () => [{}] as DOMRectList;
  document.body.appendChild(element);
  return element;
}

async function flushPromises() {
  await Promise.resolve();
  await Promise.resolve();
}

beforeEach(() => {
  vi.clearAllMocks();
  driverInstances.length = 0;
  modalState.confirms.length = 0;
  modalState.successes.length = 0;
  document.body.innerHTML = '';
  localStorage.clear();
  localStorage.setItem('payload', JSON.stringify({ userID: 1, roleID: 1 }));
  routerState.currentRoute.value.path = '/client/list';
  routerState.push.mockResolvedValue(undefined);
  routerState.afterEach.mockReturnValue(() => {});
  createTestAnchor('sidebar-nav');
  createTestAnchor('client-create');
  createTestAnchor('client-contacts-tab');
  createTestAnchor('approval-tabs');
});

describe('useTutorial', () => {
  it('支持从指定步骤开始，并清除该教程的中断记录', async () => {
    TutorialStorage.markTutorialResume('quick-start', 3);
    const { startTutorial } = useTutorial();

    await startTutorial(tutorials[0], 2);

    expect(mockTutorialEnable).toHaveBeenCalled();
    expect(TutorialStorage.getTutorialResume('quick-start')).toBeNull();
    expect(driverInstances[0].instance.highlight).toHaveBeenCalledWith(
      expect.objectContaining({
        element: document.querySelector('[data-tour="client-contacts-tab"]'),
      }),
    );
  });

  it('气泡注入跳过按钮，点击后进入下一步并统计跳过', async () => {
    const { startTutorial } = useTutorial();
    await startTutorial(tutorials[0], 0);

    const highlightedStep = driverInstances[0].instance.highlight.mock
      .calls[0][0] as {
      popover?: {
        onPopoverRender?: (options: PopoverRenderOptions) => void;
      };
    };
    const wrapper = document.createElement('div');
    const footer = document.createElement('footer');
    const description = document.createElement('div');
    const closeButton = document.createElement('button');
    wrapper.appendChild(closeButton);
    highlightedStep.popover?.onPopoverRender?.({
      wrapper,
      footer,
      description,
      closeButton,
    });

    const skipButton = footer.querySelector<HTMLButtonElement>(
      '.tutorial-skip-btn',
    );
    skipButton?.click();
    await flushPromises();

    expect(driverInstances[0].instance.highlight).toHaveBeenCalledTimes(2);
    driverInstances[0].config.onCloseClick?.();
    await flushPromises();
    modalState.confirms.at(-1)?.onOk?.();
    await flushPromises();
    expect(mockWarning).toHaveBeenCalledWith(
      expect.stringContaining('已跳过 1 步'),
    );
  });

  it('退出前确认，确认后写入当前步骤作为续学点', async () => {
    const { startTutorial } = useTutorial();
    await startTutorial(tutorials[0], 0);

    driverInstances[0].config.onCloseClick?.();
    await flushPromises();
    modalState.confirms.at(-1)?.onOk?.();
    await flushPromises();

    expect(mockTutorialDisable).toHaveBeenCalled();
    expect(TutorialStorage.getTutorialResume('quick-start')).toBe(0);
  });

  it('最后一步完成后展示推荐下一篇的结果弹层', async () => {
    const { startTutorial } = useTutorial();
    await startTutorial(tutorials[0], tutorials[0].steps.length - 1);

    driverInstances[0].config.onNextClick?.();
    await flushPromises();

    expect(TutorialStorage.isTutorialDone('quick-start')).toBe(true);
    expect(TutorialStorage.getTutorialResume('quick-start')).toBeNull();
    expect(modalState.successes[0].content).toContain('推荐下一篇');
    expect(modalState.successes[0].content).toContain(
      '新建客户：手填与 Excel 导入',
    );
  });
});
