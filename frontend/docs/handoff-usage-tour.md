# 交接文档：使用教程体验优化

> 更新时间：2026-09-09。  
> 本文档交接 `feat/usage-tour` 分支上「使用教程第三轮优化」的完成状态、实现决策、验证结果和后续工作。

## 1. 当前结论

- 上一轮主要任务是执行 OpenSpec 提案 `spec/changes/update-usage-tour-optimization/`，16 项任务全部标记完成。
- 功能实现与规格状态已提交，文档检查点也已提交。
- 分支当前无未提交的本轮功能改动；工作树中仍有若干与本任务无关的未跟踪目录/文件，交接时不要顺手提交。
- 最新功能提交：`296837a feat(tutorial): 优化教程引导体验`。
- 最新文档提交：`2a9e551 docs(tutorial): 补充教程体验验收检查点`。

## 2. 分支与提交脉络

| 提交 | 说明 |
|---|---|
| `062099a` | 新增「使用教程体验优化」OpenSpec 提案 |
| `b4707b1` | 提案补充教程步骤展开与直跳需求 |
| `296837a` | 实现引擎体验、步骤直达、断点续学、内容分点和测试 |
| `2a9e551` | 补充教程体验优化验收检查点 |

此前分支还包含基础教程、演示数据真实增删查改等内容；本交接只重点记录第三轮体验优化，不再重复早期细节。

## 3. 功能范围与用户可见行为

### 引擎体验

- 锚点不能立即找到时，显示居中「正在加载…」提示气泡；锚点渲染后自动切换到真实步骤气泡。
- 锚点等待超过 10 秒时不再静默跳过，而是让用户选择「跳过此步」或「结束教程」。
- 非最后一步的气泡页脚提供「跳过此步」；点击后立即进入下一步，并在结束时汇总跳过数量。
- `ESC`、气泡 ×、`stopTutorial()` 都先触发确认弹窗；确认后才退出并关闭演示模式。
- 非最后一步的气泡右上角有「收起 / 展开」控件；收起状态只影响当前步骤，切步后恢复完整气泡。
- 自由操作步骤离开目标路由时弹出确认，可选「返回当前页」或「结束教程」。

### 教程抽屉

- 条目显示推荐序号、步骤数、预计时长；预计时长按 `Math.max(1, Math.ceil(steps.length * 0.8))` 计算。
- 条目主体可展开/收起，展开后显示「第 N 步 · 标题」。
- 点击任意步骤可直接从该步开始，`startTutorial(tutorial, startStep)` 支持指定起始步。
- 从任意步骤启动都会启用演示模式和演示种子数据，避免直接跳到依赖前序数据的步骤时无数据可用。
- 未完成教程如果有中断记录，主按钮显示「继续（第 N 步）」，并提供「从头开始」。

### 完成与续学

- 提前退出会按用户维度写入 `tour.resume.<tutorialId>.<userID>`。
- 完成教程会清除续学记录、写入完成标记，并弹出结果层。
- 结果层显示教程标题、完成步数和推荐下一篇；点「去学习」可直接开始推荐教程。
- 推荐顺序：优先同分类下一篇；没有则进入下一个分类的第一篇。

### 内容渲染

- `TourStep.content` 支持 `string | string[]`。
- 字符串内容安全转义；数组内容逐项渲染为带「•」的独立行。
- 多个实操长文案已精简为分点，降低小气泡中的阅读密度。

## 4. 关键实现与设计决策

| 文件 | 说明 |
|---|---|
| `src/hooks/useTutorial.ts` | 教程引擎单例。管理 driver、加载提示、确认弹窗、跳过统计、路由监听、聚光灯和高亮 class 补偿。 |
| `src/components/tutorial/TutorialDrawer.vue` | 教程抽屉。负责分类、展开步骤目录、直跳、续学状态和完成标记刷新。 |
| `src/constants/tour/index.ts` | 教程配置。新增时长估算、推荐下一篇 helper，并移除 `advanceOnRoute`。 |
| `src/utils/tutorialContent.ts` | 内容格式化。数组转安全 HTML 分点，字符串转义。 |
| `src/utils/tutorialStorage.ts` | 按 userID 存储首次弹出、完成、续学状态。 |
| `src/styles/driver-theme.css` | 气泡主题、跳过按钮、收起按钮、小屏宽度、加载态和分点样式。 |
| `src/hooks/__tests__/useTutorial.test.ts` | 引擎单测：直跳清续学、跳过统计、退出续学、完成推荐。 |
| `src/utils/__tests__/tutorialContent.test.ts` | 内容转义与分点渲染单测。 |
| `src/utils/__tests__/tutorialStorage.test.ts` | 存储单测，含续学按账号/教程隔离。 |
| `src/constants/tour/__tests__/tourConfig.test.ts` | 配置完整性测试，含 `advanceOnRoute` 消失断言。 |

重要行为约束：

1. driver.js 1.8 的 `highlight()` 每次都会重建气泡 DOM，因此跳过/收起按钮通过 `onPopoverRender` 注入，不依赖上一次 DOM。
2. driver 配置 `allowClose: false`，内部 ESC 关闭被禁用；ESC 由 `window` keydown 接管，统一进入确认弹窗。
3. 锚点等待期间创建固定定位的 `#tutorial-loading-anchor`，等待结束后移除并重新 `highlight()` 真实锚点。
4. 确认弹窗使用 `Modal.confirm`，`zIndex` 为 `10000`，并通过 Promise 缓存防止重复弹出。
5. `freeInteract` 步骤只在有 `route` 时注册 `router.afterEach`；切步、退出、结束都会清理监听。
6. `advanceOnRoute` 已从类型、配置和引擎分支中完全移除，避免死代码。
7. 断点续学在 `startTutorial()` 开始后立即清除，避免旧记录影响新一轮学习。

## 5. 验证结果

已在本分支验证：

```bash
pnpm precommit:check
pnpm exec vitest run src/utils/__tests__ src/constants/tour/__tests__ src/hooks/__tests__
```

结果：

- `pnpm precommit:check` 通过。
- 相关 vitest 结果：4 个测试文件、22 个用例全部通过。
- `docs/test-checkpoints.md` 已追加 12 条「教程体验优化」手工验收点。
- OpenSpec `update-usage-tour-optimization/tasks.json` 全部 `completed: true`、`passes: true`。
- `add-usage-tour/tasks.json` 已追加「阶段 8：第三轮反馈（教程体验优化）」并标记完成。

注意：自动化测试不等于浏览器全量走查。`docs/test-checkpoints.md` 中的 768px 小屏、真实权限、路由跑偏和完成推荐场景仍建议在 dev 环境人工过一遍。

## 6. 当前工作树

`git status --short` 当前包含：

- 未跟踪：`docs/handoff-usage-tour.md`，即本交接文档。
- 未跟踪且与本任务无关：`.zcode/`、`ASSIST_PR_DESCRIPTIONS.md`、`spec/changes/add-ai-assistant-frontend/`、`spec/changes/update-sale-stage-draft-editing/`。

后续会话不应把这些无关内容混入教程相关提交；如需提交本交接文档，建议单独执行：

```bash
git add docs/handoff-usage-tour.md
git commit -F .gitmsg
```

中文提交信息建议继续写入 `.gitmsg` 后用 `git commit -F .gitmsg`，提交后删除临时文件。

## 7. 后续建议

1. 人工走查 `docs/test-checkpoints.md` 中「教程体验优化」12 条检查点，重点覆盖小屏、权限缺失、自由操作跑偏和断点续学。
2. 如果浏览器中发现确认弹窗与 driver 遮罩层级冲突，先核对 `Modal.confirm` 的 `zIndex: 10000` 与项目全局弹层层级。
3. 如果需要继续扩展教程，优先复用现有锚点校验和 `tourConfig` 测试；新增锚点必须在页面真实存在且教程内唯一。
4. 若新增依赖前序数据的步骤，确认从中间步骤直跳时 `TutorialMock.enable()` 已启用，并且演示种子数据能覆盖页面初始化。
5. `pnpm lint` 会执行 `eslint --fix`；日常校验继续用 `pnpm precommit:check`。
