# Architecture

## Directory Structure

```
src/
├── api/axios/           # Auto-generated API clients from OpenAPI spec
├── components/          # Reusable Vue components organized by domain
├── pages/               # Page-level components (file-based routing)
├── store/options/       # Vuex store modules (domain-specific)
├── router/              # Vue Router configuration
├── layout/              # Layout components (NavLayout, MainLayout)
├── utils/               # Utility functions
├── hooks/               # Custom Vue composables
├── service/             # Business logic services
├── constants/           # Application constants
└── assets/              # Static assets
```

## Key Architectural Patterns

### Auto-generated API Clients
The `src/api/axios/` directory contains auto-generated TypeScript API clients from the OpenAPI specification (`openapi.yaml`). When the backend API changes, regenerate these clients with `pnpm gen:api`.

### Domain-Driven Organization
Components, views, and store modules are organized by business domain (client, sale, user, role, permission, etc.).

### Vuex Store Modules
State management uses modular Vuex stores in `src/store/options/`:
- clientOptions
- saleOptions
- financeOptions
- contactOptions
- statisticsOptions
- privilegeOptions
- navOptions

### Component Architecture
- `components/` — Reusable components organized by domain
- `pages/` — Page-level components with file-based routing
- `layout/` — Layout wrappers (NavLayout, MainLayout)

## Testing

Tests are located in `src/utils/__tests__/` and use Vitest. Currently tests exist for utility functions like `FormDataTransfer` and `flattenObject`.

## Path Aliases

The `@` alias is configured to point to the `src/` directory. Use it for imports: `import Foo from '@/components/Foo.vue'`

## Git Worktree

When creating worktrees for multi-branch parallel work, create them under `.worktrees/` directory (e.g., `git worktree add .worktrees/feature-xxx feature-xxx`)
