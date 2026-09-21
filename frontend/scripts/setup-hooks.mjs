#!/usr/bin/env node
// 在**当前生效的 hooks 目录**内安装 `pre-commit` 转发器，转发到 frontend/.githooks/pre-commit。
//
// 为什么不写 core.hooksPath：本仓生效目录是 .git/hooks，里面已有 Qoder 遥测钩子
// （post-commit / post-checkout）。把 core.hooksPath 改到 frontend/.githooks 会静默顶掉它们，
// 反过来装到 frontend/.githooks 又会顶掉前端检查器 —— 所以两侧共存：只增加一个转发器。
// 依据 openspec/changes/operationalize-harness-gates/proposal.md 拍板记录 Q1（选项 A）。
//
// 转发器不记录仓根绝对路径，运行时自己 `git rev-parse --show-toplevel`，
// 因此在任一链接工作树里安装一次即对全部工作树生效（.git/hooks 由主仓共享）。
import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import process from 'node:process';
import { fileURLToPath } from 'node:url';

const MARKER = 'managed-by: frontend/scripts/setup-hooks.mjs';
const FORWARDER = `#!/bin/sh
# ${MARKER}
# 前端 pre-commit 转发器。阻断能力全部来自 exec 的退出码原样传出，不要改成后台执行。
# frontend/.githooks/pre-commit 自带"无 staged 前端文件即 exit 0"短路，这里不重复判断。
# 请勿手工编辑：改 frontend/scripts/setup-hooks.mjs 后重跑安装。
repo_root=$(git rev-parse --show-toplevel 2>/dev/null) || exit 0
target=$repo_root/frontend/.githooks/pre-commit
[ -f "$target" ] || exit 0
exec sh "$target" "$@"
`;

const scriptDir = path.dirname(fileURLToPath(import.meta.url));
const frontendDir = path.resolve(scriptDir, '..');
const forwardTarget = path.join(frontendDir, '.githooks', 'pre-commit');
const uninstall = process.argv.includes('--uninstall');

function git(args, cwd) {
  return execFileSync('git', args, { cwd, encoding: 'utf8' }).trim();
}

// 被转发目标不存在 → 静默跳过（前端目录可能是被裁剪出来的子集）。
if (!fs.existsSync(forwardTarget)) {
  console.log('[setup-hooks] 未找到 frontend/.githooks/pre-commit，跳过转发器安装。');
  process.exit(0);
}

let repoRoot;
try {
  repoRoot = git(['rev-parse', '--show-toplevel'], frontendDir);
} catch {
  console.error('[setup-hooks] frontend/ 不在 git 仓库内，跳过钩子注册。');
  process.exit(1);
}

// 生效 hooks 目录：core.hooksPath 优先，未设则为 $GIT_DIR/hooks。
// --git-path 把两种情况统一解析掉，相对路径按仓根展开。
const hooksDir = path.resolve(repoRoot, git(['rev-parse', '--git-path', 'hooks'], repoRoot));
const forwarderPath = path.join(hooksDir, 'pre-commit');

function sameDir(a, b) {
  const real = (p) => {
    try {
      return fs.realpathSync(p);
    } catch {
      return path.resolve(p);
    }
  };
  return real(a) === real(b);
}

if (sameDir(hooksDir, path.dirname(forwardTarget))) {
  console.log(`[setup-hooks] 生效 hooks 目录就是 frontend/.githooks（${hooksDir}），检查器已被 git 直接调用，无需转发器。`);
  process.exit(0);
}

function readExisting() {
  try {
    return fs.readFileSync(forwarderPath, 'utf8');
  } catch (err) {
    if (err.code === 'ENOENT') return null;
    throw err;
  }
}

if (uninstall) {
  const existing = readExisting();
  if (existing === null) {
    console.log(`[setup-hooks] ${forwarderPath} 不存在，无需卸载。`);
    process.exit(0);
  }
  if (!existing.includes(MARKER)) {
    console.error(`[setup-hooks] 拒绝卸载：${forwarderPath} 不是本安装器产生的（缺标记 ${MARKER}）。`);
    console.error('  它属于其他工具。确要移除请人工处置，本脚本不会删除别人的钩子。');
    process.exit(1);
  }
  fs.unlinkSync(forwarderPath);
  console.log(`[setup-hooks] 已卸载 ${forwarderPath}`);
  console.log('[setup-hooks] 未改动 core.hooksPath，同目录内其他钩子原样保留。');
  process.exit(0);
}

const existing = readExisting();
if (existing !== null && !existing.includes(MARKER)) {
  console.error(`[setup-hooks] 拒绝覆盖：${forwarderPath} 已存在且不是本安装器产生的（缺标记 ${MARKER}）。`);
  console.error('  说明生效 hooks 目录里已有第三方 pre-commit。处置方式二选一，都不要静默丢弃对方：');
  console.error('  1) 把第三方检查并入 frontend/.githooks/pre-commit，然后删除该文件并重跑本安装器；');
  console.error('  2) 保留第三方钩子，放弃本转发器（此时前端检查退回手工跑 pnpm precommit:check）。');
  process.exit(1);
}

fs.writeFileSync(forwarderPath, FORWARDER, { mode: 0o755, flag: 'w' });
try {
  fs.chmodSync(forwarderPath, 0o755);
} catch {
  // Windows 上执行位无意义，Git for Windows 按 shebang 用内置 bash 调用。
}

const configuredHooksPath = (() => {
  try {
    return git(['config', '--show-origin', '--get', 'core.hooksPath'], repoRoot);
  } catch {
    return '(未设置，走缺省 $GIT_DIR/hooks)';
  }
})();
const neighbors = fs
  .readdirSync(hooksDir)
  .filter((f) => !f.endsWith('.sample'))
  .sort()
  .join(', ');

console.log(`[setup-hooks] ${existing === null ? '已安装' : '已刷新'}转发器：${forwarderPath}`);
console.log(`[setup-hooks] 转发目标：${path.relative(repoRoot, forwardTarget).split(path.sep).join('/')}`);
console.log(`[setup-hooks] 生效 hooks 目录 = ${hooksDir}（core.hooksPath 未被本安装器改动：${configuredHooksPath}）`);
console.log(`[setup-hooks] 该目录内非 sample 钩子：${neighbors}`);
