import { readFileSync, readdirSync, statSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { join } from 'node:path';
import { describe, expect, it } from 'vitest';
import {
  getRecommendedNextTutorial,
  getTutorialDurationMinutes,
  tourCategories,
  tutorials,
} from '@/constants/tour';
import pathTitleMap from '@/constants/pathTitleMap';

/** 教程步骤允许跳转的路由（必须与 src/pages 下的真实页面一致） */
const KNOWN_ROUTES = [
  '/client/list',
  '/client/contacts',
  '/sale/list',
  '/sale/stage-approval',
  '/sale/contract',
  '/finance',
  '/contact/task',
  '/contact/activity',
  '/org',
  '/privilege/role',
  '/privilege/user',
  '/assist',
  '/assist-applications',
];

const SRC_ROOT = fileURLToPath(new URL('../../../', import.meta.url));

/** 递归收集源码里静态书写的 data-tour 锚点（动态 menu-* 锚点单独校验） */
function collectStaticAnchors(dir: string, into = new Set<string>()): Set<string> {
  for (const entry of readdirSync(dir)) {
    const full = join(dir, entry);
    if (statSync(full).isDirectory()) {
      collectStaticAnchors(full, into);
    } else if (/\.(vue|ts)$/.test(entry)) {
      const content = readFileSync(full, 'utf8');
      for (const match of content.matchAll(/data-tour="([a-zA-Z0-9_-]+)"/g)) {
        into.add(match[1]);
      }
    }
  }
  return into;
}

const staticAnchors = collectStaticAnchors(SRC_ROOT);
const menuKeys = Object.keys(pathTitleMap);

/** 校验单个锚点是否可在页面上渲染出来 */
function expectAnchorResolvable(tutorialId: string, anchor: string) {
  if (staticAnchors.has(anchor)) {
    return;
  }
  const menuMatch = /^menu-([a-z-]+)$/.exec(anchor);
  if (menuMatch && menuKeys.includes(menuMatch[1])) {
    return;
  }
  throw new Error(
    `教程 ${tutorialId} 的锚点 "${anchor}" 在源码中找不到对应的 data-tour（也不是合法的 menu-* 菜单锚点）`,
  );
}

describe('教程配置完整性', () => {
  it('三个分类都有教程，且分类值合法', () => {
    const categoryKeys = tourCategories.map((c) => c.key);
    for (const tutorial of tutorials) {
      expect(categoryKeys).toContain(tutorial.category);
    }
    for (const key of categoryKeys) {
      const count = tutorials.filter((t) => t.category === key).length;
      expect(count, `分类 ${key} 应至少有 1 篇教程`).toBeGreaterThan(0);
    }
  });

  it('教程 id 唯一且基础信息完整', () => {
    const ids = tutorials.map((t) => t.id);
    expect(new Set(ids).size).toBe(ids.length);
    for (const tutorial of tutorials) {
      expect(tutorial.title.trim()).toBeTruthy();
      expect(tutorial.description.trim()).toBeTruthy();
      expect(tutorial.steps.length, `${tutorial.id} 应至少有 1 步`).toBeGreaterThan(0);
    }
  });

  it('每一步的 route 在已知路由清单内（省略 route 表示停留当前页）', () => {
    for (const tutorial of tutorials) {
      for (const step of tutorial.steps) {
        if (step.route !== undefined) {
          expect(KNOWN_ROUTES).toContain(step.route);
        }
      }
    }
  });

  it('每一步的标题、内容、锚点非空，且锚点在教程内唯一', () => {
    for (const tutorial of tutorials) {
      const anchors = tutorial.steps.map((step) => step.anchor);
      expect(new Set(anchors).size).toBe(anchors.length);
      for (const step of tutorial.steps) {
        expect(step.title.trim()).toBeTruthy();
        if (typeof step.content === 'string') {
          expect(step.content.trim()).toBeTruthy();
        } else {
          expect(step.content.length).toBeGreaterThan(0);
          expect(step.content.every((line) => line.trim().length > 0)).toBe(true);
        }
        expect(step.anchor.trim()).toBeTruthy();
      }
    }
  });

  it('不再保留已废弃的 advanceOnRoute 配置', () => {
    const hasAdvanceOnRoute = tutorials.some((tutorial) =>
      tutorial.steps.some((step) => 'advanceOnRoute' in step),
    );
    expect(hasAdvanceOnRoute).toBe(false);
  });

  it('预计时长按步骤数估算且不小于 1 分钟', () => {
    for (const tutorial of tutorials) {
      expect(getTutorialDurationMinutes(tutorial)).toBe(
        Math.max(1, Math.ceil(tutorial.steps.length * 0.8)),
      );
    }
  });

  it('完成后的推荐下一篇按分类顺序选取', () => {
    const quickStart = tutorials.find((tutorial) => tutorial.id === 'quick-start');
    const lastBasic = tutorials.find((tutorial) => tutorial.id === 'basic-org-permission');
    const lastAdvanced = tutorials.find((tutorial) => tutorial.id === 'advanced-data-scope');
    expect(quickStart && getRecommendedNextTutorial(quickStart)?.id).toBe('basic-create-client');
    expect(lastBasic && getRecommendedNextTutorial(lastBasic)?.id).toBe('advanced-assist');
    expect(lastAdvanced && getRecommendedNextTutorial(lastAdvanced)).toBeNull();
  });

  it('每一步的锚点都能在页面源码中找到', () => {
    for (const tutorial of tutorials) {
      for (const step of tutorial.steps) {
        expectAnchorResolvable(tutorial.id, step.anchor);
      }
    }
  });

  it('菜单锚点依赖的 SideBarNav 动态绑定仍然存在', () => {
    const sidebar = readFileSync(
      join(SRC_ROOT, 'layout/components/SideBarNav.vue'),
      'utf8',
    );
    expect(sidebar).toContain('`menu-${key}`');
  });
});
