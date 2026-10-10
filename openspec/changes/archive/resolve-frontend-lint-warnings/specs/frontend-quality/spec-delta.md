# Frontend Quality Spec Delta: resolve-frontend-lint-warnings

## 1. Scope and Target
本规范定义 `frontend/` 目录下的 Vue 组件模板与 ESLint 规范约束升级：把「`pnpm lint:check` 零 warning」固化为可判定、可复验的前端质量门禁状态。

## 2. Rules and Constraints

### 2.1 Vue 属性书写顺序规范 (`vue/attributes-order`)
元素与组件标签上的属性必须遵循 Vue 官方推荐优先级顺序：
1. 定义 (`is`)
2. 列表渲染 (`v-for`)
3. 条件渲染 (`v-if`, `v-else-if`, `v-else`, `v-show`, `v-cloak`)
4. 渲染修饰符 (`v-pre`, `v-once`)
5. 全局感知 (`id`)
6. 唯一属性 (`ref`, `key`)
7. 双向绑定 (`v-model`)
8. 其他属性（静态 `class`、动态 `:class`、`:disabled` 等普通属性）
9. 事件监听 (`@click`, `@press-enter`, `@change` 等)
10. 内容修饰符 (`v-html`, `v-text`)

**禁止反例**：在 `@click` 等事件监听器之后声明 `class`、`:class` 或 `:disabled`。

### 2.2 事件命名规范 (`vue/v-on-event-hyphenation`)
组件事件在模板内一律采用 kebab-case 格式，如 `@press-enter`，禁止使用 `@pressEnter`。

### 2.3 模板作用域命名规范 (`vue/no-template-shadow`)
在 `v-for` 循环中，禁止使用 `ref` 作为迭代项变量名，避免与 Vue 的 `ref` 标识符或上层模板引用发生命名遮蔽。统一采用 `reference` 或领域明确的具体名词。

### 2.4 测试环境单组件限制豁免 (`vue/one-component-per-file`)
单文件单组件规则适用于生产源码目录。对于 `src/**/__tests__/**` 及 `src/**/*.{spec,test}.ts` 测试代码，允许在同一测试文件内定义辅助 mock/stub 组件。
