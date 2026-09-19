import { defineConfig, devices } from '@playwright/test';
import { loadE2eEnv } from './e2e/env.js';

loadE2eEnv();

const basePath = process.env.BASE_PATH || '';
const baseURL = `http://localhost:5173${basePath}`;

export default defineConfig({
  testDir: './e2e',
  timeout: 60_000,
  fullyParallel: false,
  retries: 2,
  reporter: [['list']],
  use: {
    baseURL,
    trace: 'on-first-retry',
    screenshot: 'only-on-failure',
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
  webServer: {
    command: 'pnpm dev',
    url: `${baseURL}/`,
    reuseExistingServer: true,
    timeout: 120_000,
  },
});
