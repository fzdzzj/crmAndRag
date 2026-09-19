import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { TutorialStorage } from '@/utils/tutorialStorage';
import { TokenManager } from '@/utils/token';

/** node 环境没有 localStorage，用内存实现替换（保持 get/set/remove 语义） */
function createMemoryStorage() {
  const store = new Map<string, string>();
  return {
    getItem: (key: string) => (store.has(key) ? (store.get(key) as string) : null),
    setItem: (key: string, value: string) => {
      store.set(key, String(value));
    },
    removeItem: (key: string) => {
      store.delete(key);
    },
    clear: () => {
      store.clear();
    },
  };
}

/** TokenManager.getPayload 从 localStorage 的 payload 读取 JSON */
function loginAs(userID: number) {
  localStorage.setItem('payload', JSON.stringify({ userID, roleID: 1 }));
}

beforeEach(() => {
  vi.stubGlobal('localStorage', createMemoryStorage());
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('TutorialStorage', () => {
  it('首次弹出标记默认未读，写入后为已读', () => {
    expect(TutorialStorage.hasSeenIntro()).toBe(false);
    TutorialStorage.markIntroSeen();
    expect(TutorialStorage.hasSeenIntro()).toBe(true);
  });

  it('教程完成标记按 tutorialId 记录与查询', () => {
    expect(TutorialStorage.isTutorialDone('quick-start')).toBe(false);
    TutorialStorage.markTutorialDone('quick-start');
    expect(TutorialStorage.isTutorialDone('quick-start')).toBe(true);
    expect(TutorialStorage.isTutorialDone('basic-create-client')).toBe(false);
  });

  it('同一浏览器不同账号的完成标记互不可见', () => {
    loginAs(1);
    TutorialStorage.markTutorialDone('quick-start');
    expect(TutorialStorage.isTutorialDone('quick-start')).toBe(true);

    loginAs(2);
    expect(TutorialStorage.isTutorialDone('quick-start')).toBe(false);
    TutorialStorage.markTutorialDone('quick-start');
    expect(TutorialStorage.isTutorialDone('quick-start')).toBe(true);

    loginAs(1);
    expect(TutorialStorage.isTutorialDone('quick-start')).toBe(true);
  });

  it('未登录时标记落在 anonymous 键下，不影响登录用户', () => {
    TutorialStorage.markIntroSeen();
    expect(TutorialStorage.hasSeenIntro()).toBe(true);

    loginAs(7);
    expect(TutorialStorage.hasSeenIntro()).toBe(false);
  });

  it('标记键名包含 userID，便于排查', () => {
    loginAs(42);
    TutorialStorage.markTutorialDone('quick-start');
    expect(localStorage.getItem('tour.done.quick-start.42')).toBe('1');
  });

  it('与 TokenManager 共享同一 localStorage 桩', () => {
    loginAs(9);
    expect(TokenManager.getUserID()).toBe(9);
  });

  it('断点续学按账号和教程隔离，并可清除', () => {
    loginAs(11);
    expect(TutorialStorage.getTutorialResume('quick-start')).toBeNull();

    TutorialStorage.markTutorialResume('quick-start', 4);
    expect(TutorialStorage.getTutorialResume('quick-start')).toBe(4);
    expect(localStorage.getItem('tour.resume.quick-start.11')).toBe('4');

    loginAs(12);
    expect(TutorialStorage.getTutorialResume('quick-start')).toBeNull();

    loginAs(11);
    TutorialStorage.clearTutorialResume('quick-start');
    expect(TutorialStorage.getTutorialResume('quick-start')).toBeNull();
  });
});
