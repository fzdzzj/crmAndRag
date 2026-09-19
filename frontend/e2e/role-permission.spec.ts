import { test, expect } from '@playwright/test';
import { loadE2eEnv } from './env';

loadE2eEnv();

test('E2E-S05 销售员侧边栏不显示权限管理菜单', async ({ page }) => {
  await page.goto('/#/login');

  const email = process.env.E2E_SALES_EMAIL;
  const password = process.env.E2E_SALES_PASSWORD;
  expect(email, '缺少 E2E_SALES_EMAIL 环境变量').toBeTruthy();
  expect(password, '缺少 E2E_SALES_PASSWORD 环境变量').toBeTruthy();

  await page.getByPlaceholder('请输入您的邮箱').fill(email as string);
  await page.getByPlaceholder('请输入您的密码').fill(password as string);
  await page.getByRole('button', { name: /登\s*录/ }).click();

  // 登录成功后进入业务页面，侧边栏菜单出现
  await expect(page.getByRole('menu')).toBeVisible({ timeout: 30_000 });
  await expect(page.getByRole('menuitem').first()).toBeVisible();
  // 销售员不能看到“权限管理”
  await expect(
    page.getByRole('menuitem', { name: '权限管理' }),
  ).toHaveCount(0);
});
