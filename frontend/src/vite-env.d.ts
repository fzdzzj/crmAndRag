/**
 * Vite环境变量类型声明
 */
/// <reference types="vite/client" />

interface ImportMetaEnv {
  readonly DEV: boolean;
  readonly PROD: boolean;
  readonly MODE: string;
  readonly BASE_URL: string;
  readonly BASE_API: string;
  readonly BASE_PATH: string;
  // 可以添加更多的环境变量类型声明
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
