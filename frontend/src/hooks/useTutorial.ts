import { ref } from 'vue';
import { useRouter } from 'vue-router';
import { driver } from 'driver.js';
import type { Driver } from 'driver.js';
import { Modal, message } from 'ant-design-vue';
import 'driver.js/dist/driver.css';
import '@/styles/driver-theme.css';
import type { Tutorial } from '@/constants/tour';
import { getRecommendedNextTutorial } from '@/constants/tour';
import { TutorialStorage } from '@/utils/tutorialStorage';
import { TutorialMock } from '@/utils/tutorialMock';
import { formatTutorialContent } from '@/utils/tutorialContent';

const ANCHOR_SELECTOR_TEMPLATE = '[data-tour="{anchor}"]';
const WAIT_FOR_ANCHOR_TIMEOUT = 10_000;
const CONFIRM_Z_INDEX = 10_000;

const isDrawerOpen = ref(false);

let activeTutorial: Tutorial | null = null;
let currentStepIndex = 0;
let skippedAnchors: string[] = [];
let driverInstance: Driver | null = null;
let isFinishing = false;
let isWaitingAnchor = false;
let loadingElement: HTMLDivElement | null = null;
let freeInteractWatcherStop: (() => void) | null = null;
let escapeListener: ((event: KeyboardEvent) => void) | null = null;
let lastHighlighted: HTMLElement | null = null;
let highlightClassObserver: MutationObserver | null = null;
let pinnedClickable = false;
let activeConfirmation: Promise<boolean> | null = null;
let spotlightEl: HTMLDivElement | null = null;
let spotlightTarget: HTMLElement | null = null;
let spotlightListening = false;

interface ConfirmOptions {
  title: string;
  content: string;
  okText: string;
  cancelText: string;
}

function requestConfirmation(options: ConfirmOptions) {
  if (activeConfirmation) {
    return activeConfirmation;
  }

  activeConfirmation = new Promise<boolean>((resolve) => {
    Modal.confirm({
      zIndex: CONFIRM_Z_INDEX,
      title: options.title,
      content: options.content,
      okText: options.okText,
      cancelText: options.cancelText,
      onOk: () => resolve(true),
      onCancel: () => resolve(false),
    });
  });

  void activeConfirmation.finally(() => {
    activeConfirmation = null;
  });
  return activeConfirmation;
}

function repositionSpotlight() {
  if (!spotlightTarget) {
    return;
  }
  if (!spotlightTarget.isConnected) {
    hideSpotlight();
    return;
  }
  showSpotlight(spotlightTarget);
}

function ensureSpotlight(): HTMLDivElement {
  if (!spotlightEl) {
    spotlightEl = document.createElement('div');
    spotlightEl.style.position = 'fixed';
    spotlightEl.style.pointerEvents = 'none';
    spotlightEl.style.zIndex = '9998';
    spotlightEl.style.borderRadius = '10px';
    spotlightEl.style.transition = 'all 0.2s ease';
    spotlightEl.style.boxShadow = '0 0 0 9999px rgba(15, 23, 42, 0.4)';
    document.body.appendChild(spotlightEl);
  }
  return spotlightEl;
}

function showSpotlight(element: HTMLElement) {
  const spotlight = ensureSpotlight();
  const rect = element.getBoundingClientRect();
  const padding = 6;
  spotlight.style.display = 'block';
  spotlight.style.left = `${rect.left - padding}px`;
  spotlight.style.top = `${rect.top - padding}px`;
  spotlight.style.width = `${rect.width + padding * 2}px`;
  spotlight.style.height = `${rect.height + padding * 2}px`;
}

function hideSpotlight() {
  if (spotlightEl) {
    spotlightEl.style.display = 'none';
  }
}

function watchHighlightClasses(element: HTMLElement) {
  highlightClassObserver?.disconnect();
  pinnedClickable = element.classList.contains('tutorial-clickable');
  highlightClassObserver = new MutationObserver(() => {
    if (!element.classList.contains('tutorial-active-target')) {
      element.classList.add('tutorial-active-target');
    }
    if (pinnedClickable && !element.classList.contains('tutorial-clickable')) {
      element.classList.add('tutorial-clickable');
    }
  });
  highlightClassObserver.observe(element, { attributes: true, attributeFilter: ['class'] });
}

function unwatchHighlightClasses() {
  highlightClassObserver?.disconnect();
  highlightClassObserver = null;
  pinnedClickable = false;
}

function clearLastHighlighted() {
  unwatchHighlightClasses();
  if (!lastHighlighted) {
    return;
  }
  lastHighlighted.classList.remove(
    'driver-active-element',
    'driver-no-interaction',
    'tutorial-active-target',
    'tutorial-clickable',
  );
  lastHighlighted.parentElement?.classList.remove(
    'driver-active-element-parent',
    'driver-active-element-parent-no-scroll',
  );
  lastHighlighted = null;
}

function clearFreeInteractWatcher() {
  freeInteractWatcherStop?.();
  freeInteractWatcherStop = null;
}

function showLoadingPopover() {
  if (!driverInstance || loadingElement) {
    return;
  }

  loadingElement = document.createElement('div');
  loadingElement.id = 'tutorial-loading-anchor';
  loadingElement.style.cssText = [
    'position:fixed',
    'left:50%',
    'top:50%',
    'width:280px',
    'height:120px',
    'transform:translate(-50%, -50%)',
    'z-index:9998',
  ].join(';');
  document.body.appendChild(loadingElement);

  driverInstance.highlight({
    element: loadingElement,
    disableActiveInteraction: true,
    popover: {
      title: '正在加载…',
      description: '正在定位教程目标，请稍候。',
      popoverClass: 'tutorial-popover tutorial-loading',
      showButtons: [],
      showProgress: false,
    },
  });
}

function hideLoadingPopover() {
  loadingElement?.remove();
  loadingElement = null;
}

export function useTutorial() {
  const router = useRouter();

  function openDrawer() {
    isDrawerOpen.value = true;
  }

  function closeDrawer() {
    isDrawerOpen.value = false;
  }

  async function requestExit() {
    if (!activeTutorial) {
      return;
    }
    const shouldExit = await requestConfirmation({
      title: '确认退出教程？',
      content: '退出后教程演示模式会关闭；中断步骤将记录，可稍后继续。',
      okText: '退出教程',
      cancelText: '继续学习',
    });
    if (shouldExit) {
      finish(false);
    }
  }

  function finish(completed: boolean) {
    const tutorial = activeTutorial;
    if (!tutorial) {
      return;
    }

    activeTutorial = null;
    isWaitingAnchor = false;
    TutorialMock.disable();
    document.body.classList.remove('tutorial-allow-all');
    clearFreeInteractWatcher();
    clearLastHighlighted();
    hideLoadingPopover();
    hideSpotlight();
    if (spotlightListening) {
      window.removeEventListener('resize', repositionSpotlight);
      window.removeEventListener('scroll', repositionSpotlight, true);
      spotlightListening = false;
    }
    spotlightTarget = null;
    if (escapeListener) {
      window.removeEventListener('keydown', escapeListener);
      escapeListener = null;
    }
    if (driverInstance) {
      isFinishing = true;
      driverInstance.destroy();
      driverInstance = null;
      isFinishing = false;
    }

    if (completed) {
      TutorialStorage.clearTutorialResume(tutorial.id);
      TutorialStorage.markTutorialDone(tutorial.id);
      showCompletion(tutorial);
    } else {
      TutorialStorage.markTutorialResume(tutorial.id, currentStepIndex);
    }

    if (skippedAnchors.length > 0) {
      message.warning(
        `已跳过 ${skippedAnchors.length} 步（可能因权限不足或页面暂无数据）`,
      );
    }
    skippedAnchors = [];
  }

  function showCompletion(tutorial: Tutorial) {
    const next = getRecommendedNextTutorial(tutorial);
    Modal.success({
      zIndex: CONFIRM_Z_INDEX,
      title: '教程已完成',
      content: `「${tutorial.title}」已完成 ${tutorial.steps.length} 步。${
        next ? `推荐下一篇：《${next.title}》。` : '目前没有更多推荐教程。'
      }`,
      okText: next ? '去学习' : '知道了',
      onOk: () => {
        if (next) {
          void startTutorial(next);
        }
      },
    });
  }

  function waitForAnchor(anchor: string): Promise<HTMLElement | null> {
    const selector = ANCHOR_SELECTOR_TEMPLATE.replace('{anchor}', anchor);
    return new Promise((resolve) => {
      let observer: MutationObserver | null = null;
      let timer: number | null = null;
      let settled = false;

      const cleanup = () => {
        observer?.disconnect();
        observer = null;
        if (timer !== null) {
          window.clearTimeout(timer);
          timer = null;
        }
      };
      const settle = (element: HTMLElement | null) => {
        if (settled) {
          return;
        }
        settled = true;
        cleanup();
        resolve(element);
      };
      const find = () => {
        if (settled) {
          return;
        }
        const element = document.querySelector<HTMLElement>(selector);
        if (element && element.getClientRects().length > 0) {
          settle(element);
        }
      };

      find();
      if (settled) {
        return;
      }
      showLoadingPopover();
      observer = new MutationObserver(find);
      observer.observe(document.documentElement, {
        childList: true,
        subtree: true,
        attributes: true,
      });
      timer = window.setTimeout(() => settle(null), WAIT_FOR_ANCHOR_TIMEOUT);
    });
  }

  async function goToStep(rawIndex: number) {
    const tutorial = activeTutorial;
    if (!tutorial || isWaitingAnchor) {
      return;
    }
    if (rawIndex >= tutorial.steps.length) {
      finish(true);
      return;
    }
    if (rawIndex < 0) {
      rawIndex = 0;
    }

    const index = rawIndex;
    currentStepIndex = index;
    const step = tutorial.steps[index];
    clearFreeInteractWatcher();
    clearLastHighlighted();
    hideSpotlight();

    if (step.route && router.currentRoute.value.path !== step.route) {
      await router.push(step.route);
    }

    isWaitingAnchor = true;
    const element = await waitForAnchor(step.anchor);
    isWaitingAnchor = false;
    hideLoadingPopover();
    if (!activeTutorial || activeTutorial.id !== tutorial.id) {
      return;
    }
    if (!element) {
      skippedAnchors.push(step.anchor);
      const shouldSkip = await requestConfirmation({
        title: '步骤加载超时',
        content: `「${step.title}」的目标内容仍未显示。可以跳过这一步，或先结束教程。`,
        okText: '跳过此步',
        cancelText: '结束教程',
      });
      if (!activeTutorial || activeTutorial.id !== tutorial.id) {
        return;
      }
      if (shouldSkip) {
        void goToStep(index + 1);
      } else {
        finish(false);
      }
      return;
    }

    const isLast = index === tutorial.steps.length - 1;
    const clickThrough = Boolean(step.clickable);
    const freeInteract = Boolean(step.freeInteract);
    document.body.classList.toggle('tutorial-allow-all', freeInteract);
    clearLastHighlighted();
    element.classList.add('tutorial-active-target');
    if (clickThrough) {
      element.classList.add('tutorial-clickable');
    }
    watchHighlightClasses(element);

    driverInstance?.highlight({
      element,
      disableActiveInteraction: !(clickThrough || freeInteract),
      advanceOnClick: !clickThrough && !freeInteract,
      popover: {
        title: step.title,
        description: '',
        showProgress: true,
        progressText: `${index + 1} / ${tutorial.steps.length}`,
        showButtons:
          index === 0
            ? ['next', 'close']
            : ['previous', 'next', 'close'],
        nextBtnText: isLast ? '完成' : '下一步',
        ...(freeInteract ? { side: 'top' as const } : {}),
        onPopoverRender: (popover) => {
          popover.description.innerHTML = formatTutorialContent(step.content);

          if (!isLast) {
            const skipButton = document.createElement('button');
            skipButton.type = 'button';
            skipButton.className = 'driver-popover-footer-btn tutorial-skip-btn';
            skipButton.textContent = '跳过此步';
            skipButton.addEventListener('click', () => {
              if (isWaitingAnchor) {
                return;
              }
              skippedAnchors.push(step.anchor);
              void goToStep(index + 1);
            });
            popover.footer.appendChild(skipButton);
          }

          if (!isLast) {
            const collapseButton = document.createElement('button');
            collapseButton.type = 'button';
            collapseButton.className = 'tutorial-collapse-btn';
            collapseButton.textContent = '收起';
            collapseButton.addEventListener('click', () => {
              const minimized = popover.wrapper.classList.toggle('tutorial-minimized');
              collapseButton.textContent = minimized ? '展开' : '收起';
            });
            popover.wrapper.insertBefore(collapseButton, popover.closeButton);
          }
        },
      },
    });

    lastHighlighted = element;
    spotlightTarget = element;
    showSpotlight(element);
    if (freeInteract) {
      document.querySelectorAll<HTMLElement>('.driver-overlay').forEach((overlay) => {
        overlay.style.pointerEvents = 'none';
      });
    }
    if (!spotlightListening) {
      window.addEventListener('resize', repositionSpotlight);
      window.addEventListener('scroll', repositionSpotlight, true);
      spotlightListening = true;
    }

    if (freeInteract && step.route) {
      const route = step.route;
      freeInteractWatcherStop = router.afterEach((to) => {
        if (to.path === route) {
          return;
        }
        void (async () => {
          const shouldReturn = await requestConfirmation({
            title: '离开教程页面',
            content: '当前步骤需要在此页操作。要返回当前页继续，还是结束教程？',
            okText: '返回当前页',
            cancelText: '结束教程',
          });
          if (!activeTutorial || activeTutorial.id !== tutorial.id) {
            return;
          }
          if (shouldReturn) {
            await router.push(route);
            return;
          }
          finish(false);
        })();
      });
    }
  }

  async function startTutorial(tutorial: Tutorial, startStep = 0) {
    if (activeTutorial) {
      finish(false);
    }

    const maxStepIndex = Math.max(0, tutorial.steps.length - 1);
    const startIndex = Number.isSafeInteger(startStep)
      ? Math.min(Math.max(startStep, 0), maxStepIndex)
      : 0;
    activeTutorial = tutorial;
    currentStepIndex = startIndex;
    skippedAnchors = [];
    TutorialMock.enable();
    TutorialStorage.clearTutorialResume(tutorial.id);
    isDrawerOpen.value = false;

    escapeListener = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        event.preventDefault();
        void requestExit();
      }
    };
    window.addEventListener('keydown', escapeListener);

    driverInstance = driver({
      allowClose: false,
      allowKeyboardControl: false,
      overlayColor: '#0f172a',
      overlayOpacity: 0.4,
      animate: false,
      overlayClickBehavior: () => {},
      popoverClass: 'tutorial-popover',
      disableActiveInteraction: true,
      nextBtnText: '下一步',
      prevBtnText: '上一步',
      doneBtnText: '完成',
      onDestroyed: () => {
        if (!isFinishing) {
          finish(false);
        }
      },
      onNextClick: () => {
        void goToStep(currentStepIndex + 1);
      },
      onPrevClick: () => {
        void goToStep(currentStepIndex - 1);
      },
      onCloseClick: () => {
        void requestExit();
      },
    });
    await goToStep(startIndex);
  }

  function stopTutorial() {
    void requestExit();
  }

  return {
    isDrawerOpen,
    openDrawer,
    closeDrawer,
    startTutorial,
    stopTutorial,
    currentStepIndex: () => currentStepIndex,
  };
}
