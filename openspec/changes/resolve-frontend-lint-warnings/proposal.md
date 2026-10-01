# 提案：前端组件 Lint & 模板警告消解（零 Warning 洁净门禁）

> 变更 ID：resolve-frontend-lint-warnings ｜ 能力域：frontend ｜ 序列：前端代码质量加固
> 来源：远端 CI `Frontend Test / Lint / Type-check / Build Budget` 阶段与本地 `pnpm lint:check` 稳定报告的 22 项黄色警告。
> 基线：master=932cdb9，surefire **905**，failsafe **83**，前端单测 **163**（2026-10-01 执行期实测为 **165**，差异见 tasks.md 执行记录）。
> 执行实测（2026-10-01）：22 warning → 0 problems，`pnpm lint:check` 退出码 0。

## 1. Why

目前在 GitHub Actions 远端 CI 的 `frontend-quality` 检查中，尽管构建成功，但持续产生 7 条 GitHub Annotations 黄色警报；本地 `pnpm lint:check` 真实扫描输出：

```text
✖ 22 problems (0 errors, 22 warnings)
  0 errors and 18 warnings potentially fixable with the `--fix` option.
```

这 22 处警告集中在 3 个文件：

1. `frontend/src/components/ai/AiAssistantDrawer.vue`（5 条）：
   - `vue/attributes-order`：`ref` 未在 `class` 前、`:disabled` 未在 `@press-enter` 前；
   - `vue/no-v-html`：AI 富文本渲染触发 XSS 警告（需合理解析或加定向规则说明）；
   - `vue/no-template-shadow`：循环变量名 `ref` 遮蔽上层作用域（应改用 `reference`）；
   - `vue/v-on-event-hyphenation`：监听器名 `@pressEnter` 驼峰式违规（应为 `@press-enter`）。
2. `frontend/src/components/ai/__tests__/ErrorFeedbackButton.test.ts`（2 条）：
   - `vue/one-component-per-file`：单测文件内部为测试辅助 mock 了 Button 组件并调用了 createApp，触发单文件单组件规则，单测文件不应受此规则约束。
3. `frontend/src/pages/(dashboard)/knowledge/index.page.vue`（15 条）：
   - `vue/attributes-order` 与 `vue/first-attribute-linebreak`：标签上 `class`、`:class`、`:disabled`、`v-model` 与 `@click` 事件监听器顺序倒置或缺少换行。

消解全部 22 项警告将使前端 Lint 达到 0 errors, 0 warnings 洁净基线，彻底根除 CI 中的警告噪音。

## 2. What Changes

### 2.1 组件模板规范化修复（¥0，无行为变更）

- AiAssistantDrawer.vue：
  - 属性顺序调整：`ref="messagesRef"` 移至 `class` 之前；`:disabled="isStreaming"` 移至事件监听器之前；
  - 事件名规范：`@pressEnter` 改为 `@press-enter`（Vue 模板标准的 kebab-case，Ant Design Vue 原生支持）；
  - 循环变量重命名：`v-for="(ref, i) in msg.references"` 改为 `v-for="(reference, i) in msg.references"`，模板内插值同步更新为 `refLabel(reference)` 与 `refIdSuffix(reference)`，事件调用同步为 `jumpReference(reference)`，彻底解除变量遮蔽；
  - 富文本安全声明：为 `v-html="formatContent(msg.content)"` 补充行级 `<!-- eslint-disable-next-line vue/no-v-html -->` 及安全上下文注释。
- knowledge/index.page.vue：
  - 规范 `<button>`、`<input>`、`<span>` 等元素的属性顺序，确保静态属性与指令在事件监听器（`@click`、`@dragenter` 等）之前；
  - 补齐换行格式，对齐 ESLint Vue 规则。

### 2.2 ESLint 配置针对性调优（eslint.config.mjs）

- 为 `src/**/__tests__/**` 及 `src/**/*.{spec,test}.ts` 补充 overrides：关闭 `vue/one-component-per-file` 规则，认可测试文件中定义局部 mock/stub 组件的合理工程实践。

## 3. 验收标准

1. `pnpm lint:check` 输出 0 problems（0 errors, 0 warnings）——CLI 洁净时不打印问题清单，判定口径为「无任何 problem 行 + 退出码 0」；
2. `pnpm type-check:check` 验证通过：`vue-tsc` 0 错误；
3. `pnpm test` 验证通过：Vitest 全量 16 个测试文件全部 PASS（执行期实测 165 例；卡片所记 163 为历史值）；
4. `pnpm build` 生产打包成功；
5. 页面功能、UI 样式与交互行为等价，零回归。

## 4. 边界与风险

- **零破坏**：本项改动仅涉及代码书写规范与配置豁免，不改变组件 DOM 结构与响应式逻辑；
- **环境隔离**：仅改动 `frontend/` 目录与 `openspec/changes/resolve-frontend-lint-warnings/`，不碰后端 Java 代码、不碰 Maven 配置、不碰 Flyway 迁移。
