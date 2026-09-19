# Claude Code Automation Recommendations

## Codebase Profile
- **Type**: Vue 3 + TypeScript frontend application
- **Framework**: Vue 3 with Ant Design Vue + Tailwind CSS
- **Key Libraries**: TanStack Vue Query, Vuex, Vue Router, ECharts, Axios, Day.js
- **Build Tools**: Vite, ESLint, Prettier, Vitest, vue-tsc
- **Size**: ~77 Vue components, ~54 TS files
- **API**: Auto-generated from OpenAPI spec (~6000 lines)
- **Remotes**: GitHub + Gitee (multi-remote push)

---

## MCP Servers

### 1. context7 (already installed)
Fetches live documentation for libraries. Great fit given Ant Design Vue, TanStack Vue Query, and ECharts usage.

### 2. Playwright MCP (already installed)
Browser automation for testing CRM UI.

---

## Skills

### 1. `gen-component` -- Scaffold Vue components
**Why**: 77+ Vue components follow a consistent pattern (PascalCase, Tailwind, `<script setup>`, hooks for API calls). Scaffolding saves time.

**Create**: `.claude/skills/gen-component/SKILL.md`
```yaml
---
name: gen-component
description: Scaffold a new Vue 3 component with Tailwind styling, script setup, and proper conventions
user-invocable: true
---
```
Should generate components following CLAUDE.md rules: PascalCase naming, Tailwind only (no `<style>` tags), `<script setup lang="ts">`, API calls wrapped in hooks.

### 2. `gen-hook` -- Generate API hooks from OpenAPI
**Why**: CLAUDE.md mandates "never call API directly in components." 16 hooks already exist. A skill generating new hooks from the OpenAPI spec enforces this pattern automatically.

**Create**: `.claude/skills/gen-hook/SKILL.md`
```yaml
---
name: gen-hook
description: Generate a new composable hook wrapping API calls from the auto-generated Api.ts client
user-invocable: true
---
```
Should: read `src/api/axios/Api.ts` for available endpoints, generate hook in `src/hooks/`, include loading state and error handling, use TanStack Vue Query patterns.

---

## Hooks

### 1. Auto-format on edit
**Why**: Prettier configured (`pnpm format`). Automatically running `prettier --write` after edits prevents formatting noise in diffs.

**Where**: `.claude/settings.json`
```json
{
  "hooks": {
    "PostToolUse": [
      {
        "matcher": "Edit|Write",
        "command": "npx prettier --write \"$FILE\" 2>/dev/null"
      }
    ]
  }
}
```

### 2. Auto type-check on Vue/TS edits
**Why**: CLAUDE.md instructs "ALWAYS run type checking on the modified files." A hook ensures it never gets skipped.

**Where**: `.claude/settings.json`
```json
{
  "hooks": {
    "PostToolUse": [
      {
        "matcher": "Edit|Write",
        "command": "npx vue-tsc --noEmit \"$FILE\" 2>&1 | tail -20"
      }
    ]
  }
}
```

> **Note**: Running both hooks on every edit could be slow. Consider Prettier hook only, and type-check manually or via subagent.

---

## Subagents

### 1. `vue-reviewer` -- Vue component convention checker
**Why**: 77+ components. A specialized reviewer checking project conventions catches issues early.

**Create**: `.claude/agents/vue-reviewer.md`
```markdown
---
name: vue-reviewer
description: Reviews Vue components for project convention compliance
---

You are a Vue 3 component reviewer for a CRM project. Check:
1. No `<style>` tags (use Tailwind CSS only)
2. PascalCase component names in templates and imports
3. All Ant Design Vue components properly imported
4. No direct API calls -- must use hooks from src/hooks/
5. Date fields use yyyy-MM-dd HH:mm:ss format
6. `<script setup lang="ts">` used (not Options API)
Report violations as a numbered list.
```

### 2. `api-sync-checker` -- OpenAPI diff analyzer
**Why**: `Api.ts` is auto-generated (~6000 lines) from `openapi.yaml`. Comparing the spec with generated client identifies new/changed endpoints to keep hooks and components in sync.

**Create**: `.claude/agents/api-sync-checker.md`
```markdown
---
name: api-sync-checker
description: Analyzes openapi.yaml and checks if hooks/components are in sync
---

Compare openapi.yaml endpoints against src/hooks/ and src/api/axios/Api.ts.
Report: new endpoints without hooks, hooks referencing removed endpoints,
and breaking changes in existing endpoints.
```

---

## Plugins

### 1. `commit-commands` (already installed)
Streamlines commit/push/PR workflow across GitHub and Gitee.

### 2. `frontend-design` plugin
Vue 3 + Tailwind CRM with extensive UI work. Skills like animate, arrange, polish, audit improve UX without manual effort. Already available in environment.

---

## Summary

| Category | Top Recommendation | Effort |
|----------|-------------------|--------|
| **Hooks** | Auto-format with Prettier on edit | Low |
| **Skills** | `gen-component` scaffold | Medium |
| **Skills** | `gen-hook` scaffold from OpenAPI | Medium |
| **Subagents** | `vue-reviewer` convention checker | Low |
| **Subagents** | `api-sync-checker` | Low |
| **Plugins** | Use `frontend-design` skills more | None |
