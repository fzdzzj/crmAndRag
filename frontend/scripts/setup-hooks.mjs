#!/usr/bin/env node
// 把 frontend/.githooks 注册为仓库的 hooks 目录。
// frontend/ 只是 crmAndRag 仓里的一个子目录，所以 core.hooksPath 必须相对**仓根**写，
// 不能照搬独立前端仓时代的 `.githooks`。
import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const scriptDir = path.dirname(fileURLToPath(import.meta.url));
const frontendDir = path.resolve(scriptDir, '..');
const hookFile = path.join(frontendDir, '.githooks', 'pre-commit');

if (!fs.existsSync(hookFile)) {
  console.error(`[setup-hooks] 找不到钩子实体：${hookFile}`);
  process.exit(1);
}

let repoRoot;
try {
  repoRoot = execFileSync('git', ['rev-parse', '--show-toplevel'], {
    cwd: frontendDir,
    encoding: 'utf8',
  }).trim();
} catch {
  console.error('[setup-hooks] frontend/ 不在 git 仓库内，跳过钩子注册。');
  process.exit(1);
}

const rel = path.relative(repoRoot, frontendDir).split(path.sep).join('/');
const hooksPath = rel ? `${rel}/.githooks` : '.githooks';

execFileSync('git', ['config', 'core.hooksPath', hooksPath], { cwd: repoRoot, stdio: 'inherit' });

try {
  fs.chmodSync(hookFile, 0o755);
} catch {
  // Windows 上执行位无意义，git 仍按 core.hooksPath 调用。
}

const current = execFileSync('git', ['config', '--get', 'core.hooksPath'], {
  cwd: repoRoot,
  encoding: 'utf8',
}).trim();

console.log(`[setup-hooks] core.hooksPath = ${current}（仓根 ${repoRoot}）`);
