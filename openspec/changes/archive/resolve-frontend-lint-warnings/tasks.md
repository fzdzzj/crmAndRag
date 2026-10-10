# Tasks — resolve-frontend-lint-warnings

> 执行契约见 `frontend/AGENTS.md`。当前基线：master=932cdb9，surefire **905**，Vitest **163**（2026-10-01 执行期实测 **165**）。
> 硬约束：全量改动仅限前端工程与本案 openspec 目录，严禁修改后端 Java 代码与 Maven 配置；严禁引入任何新的依赖；改动后必须达成 0 errors, 0 warnings。

## 0. 执行记录（执行 agent 填写）
- 初始：现场 `pnpm lint:check` 实测 **22 problems (0 errors, 22 warnings)**，与卡片描述逐条一致（基线留痕见 §6）；
- 目标：全部 22 项 warning 彻底清零；
- 终态：`pnpm lint:check` 无任何 problem 行且退出码 0（22 → 0）；`pnpm type-check:check` 0 错误；`pnpm test` 16 文件全通过；`pnpm build` 成功并生成 `dist/`；
- 受控写集：仅 6 个受控文件被改（3 个前端 + 本案 3 份 openspec 文档），`git status` 无越界文件；
- 规格三件套在派发时存在字符丢失（`resolve`→`esolve`、`frontend`→`rontend`、`vue/`→`ue/`、`v-for`→`-for`、`ref`→`ef`、`0 problems`→` problems`、Markdown 代码栅栏的语言标记被破坏），本卡执行时按原意重写校正，未改动任何规则语义。

## 1. ESLint 配置调优 (completed)
- [x] 1.1 在 `frontend/eslint.config.mjs` 中为测试目录 `src/**/__tests__/**` 和测试文件 `src/**/*.{spec,test}.ts` 配置 overrides，将 `vue/one-component-per-file` 设为 `off`。｜实测：`ErrorFeedbackButton.test.ts` 的原 2 条该规则 warning 消失，且生产源码仍受该规则约束。

## 2. AiAssistantDrawer.vue 组件修复 (completed)
- [x] 2.1 规范第 21 行属性顺序：`ref="messagesRef"` 移至 `class` 之前；
- [x] 2.2 规范第 26 行 `v-html`：添加 `<!-- eslint-disable-next-line vue/no-v-html -->` 及富文本上下文安全注释；
- [x] 2.3 规范第 30 行模板循环变量名：将 `ref` 改为 `reference`，插值同步改为 `refLabel(reference)` 与 `refIdSuffix(reference)`，事件调用同步改为 `jumpReference(reference)`，消解模板作用域遮蔽；
- [x] 2.4 规范第 57-58 行输入框属性与事件：`@pressEnter` 改为 `@press-enter`，`:disabled="isStreaming"` 调整到事件监听器之前。

## 3. knowledge/index.page.vue 页面模板规范化 (completed)
- [x] 3.1 规范第 140-145 行 `<button>` 属性顺序：静态 `class` 与动态 `:class` 移至 `@click` 之前；
- [x] 3.2 规范第 154、171、181-182 行属性顺序与断行：确保 `class` 在 `@click` 之前，补齐 `<span>` 首属性换行；`--fix` 自动插入的断行缺缩进，已手工对齐为 16 空格级联缩进；
- [x] 3.3 规范第 206-240 行拖拽上传与检索测试面板：`class`、`:disabled`、`v-model` 调整至 `@click` 与 `@dragenter` 之前。

## 4. 前端本地全量复核验证 (completed)
- [x] 4.1 运行 `pnpm lint:check`，达成 0 problems（0 errors, 0 warnings）｜实测：退出码 0 且问题清单为空（原 22 条 warning 全部消失）；
- [x] 4.2 运行 `pnpm type-check:check`，断言 0 类型错误｜实测 `TYPECHECK_RC=0`，`vue-tsc --noEmit` app/config 两段均无输出；
- [x] 4.3 运行 `pnpm test`，断言 16 个测试文件全部 PASS｜实测 **Test Files 16 passed (16) / Tests 165 passed (165)**，耗时 110.90s；卡片所记 163 为历史值，实际增量来自既有提交新增用例，本卡未增删任何用例；
- [x] 4.4 运行 `pnpm build`，确证生产打包正常｜实测 `BUILD_RC=0`、`✓ built in 51.34s`，`dist/` 含 assets/index.html/ico.svg/logo.svg。

## 5. 交付与收尾 (completed)
- [x] 5.1 检查 `git status`，确认改动严格限制在受控文件内｜实测 `bash scripts/agent-helper.sh check-write-set 932cdb9 <6 文件>` → `WRITE_SET_OK: 6 files`；`check-line-endings lf <6 文件>` 全部 `LINE_ENDINGS_OK`；
- [x] 5.2 提交 feature 分支并使用 `--no-ff` 合入 master，保留分支，未获显式授权绝不 push｜未执行 `git push`。

## 6. 基线输出留痕（执行前实测，2026-10-01）
```text
✖ 22 problems (0 errors, 22 warnings)
  0 errors and 18 warnings potentially fixable with the `--fix` option.
```
分布：`AiAssistantDrawer.vue` 5 条（attributes-order ×2 / no-v-html / no-template-shadow / v-on-event-hyphenation）；`__tests__/ErrorFeedbackButton.test.ts` 2 条（one-component-per-file ×2）；`knowledge/index.page.vue` 15 条（attributes-order ×14 / first-attribute-linebreak ×1）。
