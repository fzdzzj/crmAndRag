import js from '@eslint/js';
import prettierConfig from 'eslint-config-prettier';
import vuePlugin from 'eslint-plugin-vue';
import {
  configureVueProject,
  defineConfigWithVueTs,
  vueTsConfigs,
} from '@vue/eslint-config-typescript';

configureVueProject({
  rootDir: import.meta.dirname,
  scriptLangs: ['ts'],
  tsSyntaxInTemplates: true,
  allowComponentTypeUnsafety: true,
});

export default defineConfigWithVueTs(
  [
    {
      ignores: [
        'dist/**',
        'coverage/**',
        'node_modules/**',
        'src/api/axios/**',
        '**/*.gen.ts',
        'typed-router.d.ts',
      ],
    },
    js.configs.recommended,
    ...vuePlugin.configs['flat/essential'],
    ...vuePlugin.configs['flat/recommended'],
    prettierConfig,
    {
      files: ['src/**/*.{ts,tsx,vue,js,mjs,cjs}'],
      languageOptions: {
        globals: {
          window: 'readonly',
          document: 'readonly',
          console: 'readonly',
          __dirname: 'readonly',
          __filename: 'readonly',
          require: 'readonly',
          module: 'readonly',
        },
        parserOptions: {
          ecmaVersion: 'latest',
          sourceType: 'module',
        },
      },
      rules: {
        'vue/multi-word-component-names': 'off',
        'vue/require-default-prop': 'off',
        'vue/no-reserved-component-names': 'off',
        '@typescript-eslint/consistent-type-imports': [
          'error',
          { prefer: 'type-imports', fixStyle: 'inline-type-imports' },
        ],
        '@typescript-eslint/no-explicit-any': 'error',
        '@typescript-eslint/no-unused-vars': [
          'error',
          {
            argsIgnorePattern: '^_',
            varsIgnorePattern: '^_',
            caughtErrorsIgnorePattern: '^_',
          },
        ],
      },
    },
  ],
  vueTsConfigs.recommendedTypeChecked,
);
