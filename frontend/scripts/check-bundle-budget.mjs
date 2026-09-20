#!/usr/bin/env node
// ================================================================
// 前端构建产物 gzip 体积预算门禁。
//
// 口径与仓根 scripts/check-test-baseline.sh / scripts/test-baseline.txt 完全一致：
//   阈值只允许由
//     node scripts/check-bundle-budget.mjs --update
//   在一次真实 `pnpm build` 之后写入 bundle-budget.json，禁止手改。
//   dist 缺失或没有任何 js/css 产物时 check 直接判失败——"根本没构建"不允许被读成"体积没涨"。
//
// 用法：
//   node scripts/check-bundle-budget.mjs                # 用已入库的预算裁决 frontend/dist
//   node scripts/check-bundle-budget.mjs --update       # 用当前 dist 重写阈值（现值 × 1.02）
//   node scripts/check-bundle-budget.mjs --report       # 只打印每个 chunk 的 raw/gzip 清单，不裁决
//   node scripts/check-bundle-budget.mjs --dist=<dir>   # 度量指定 dist（和 Historical 产物对照时用）
//
// 为什么阈值挂在"桶"上而不是 chunk 文件名上：Vite 的 chunk 名带内容哈希
// （statistics.page-Bfgl9wo_.js），改一行代码哈希就变、按文件名的阈值天天误报。
// 桶 = 入口(entry) + manualChunks 的三个 vendor 桶 + 其余应用代码(app) + total，
// 与 vite.config.ts 的 build.rollupOptions.output.manualChunks 命名一一对应，
// 改那边的分桶名时必须同步改下面的 VENDOR_BUCKETS。
// ================================================================

import { existsSync, readFileSync, readdirSync, statSync, writeFileSync } from 'node:fs';
import path from 'node:path';
import { execSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { gzipSync } from 'node:zlib';

const GZIP_LEVEL = 9;
const HEADROOM = 1.02; // 起步只留 2% 重构余量，涨体积要么被拦要么显式 --update 认账
const SCRIPT_DIR = path.dirname(fileURLToPath(import.meta.url));
const FRONTEND_ROOT = path.resolve(SCRIPT_DIR, '..');
const DEFAULT_DIST = path.join(FRONTEND_ROOT, 'dist');
const BUDGET_FILE = path.join(FRONTEND_ROOT, 'bundle-budget.json');
const VENDOR_BUCKETS = ['vendor-antd', 'vendor-echarts', 'vendor'];
const BUCKETS = ['entry', ...VENDOR_BUCKETS, 'app', 'total'];
const ASSET_EXTS = new Set(['.js', '.mjs', '.css']);

const args = process.argv.slice(2);
const MODE = args.includes('--update') ? 'update' : args.includes('--report') ? 'report' : 'check';
const distArg = args.find((a) => a.startsWith('--dist='));
const distDir = distArg ? path.resolve(distArg.slice('--dist='.length)) : DEFAULT_DIST;

function fail(message) {
  console.log(`::error::${message}`);
  process.exitCode = 1;
}

function kb(bytes) {
  return (bytes / 1024).toFixed(2);
}

function walk(dir) {
  const out = [];
  for (const entry of readdirSync(dir)) {
    const full = path.join(dir, entry);
    if (statSync(full).isDirectory()) out.push(...walk(full));
    else out.push(full);
  }
  return out;
}

// assets/index-BaJ0YVJr.js -> { chunk: 'index', ext: '.js' }；Vite 的哈希固定 8 位，
// 末尾 `-` + 8 个哈希字符整体剥掉，剩下的就是逻辑 chunk 名。
function chunkOf(file) {
  const base = path.basename(file);
  const ext = path.extname(base);
  const stripped = base.slice(0, -ext.length).replace(/-[A-Za-z0-9_-]{8}$/, '');
  return { chunk: stripped, ext };
}

// 入口 chunk 从 dist/index.html 反查（脚本标签 + 样式标签），不靠命名猜——
// 应用代码里也有一堆 index-<hash>.js，按名字区分不了入口和普通 chunk。
function entryChunkFiles(dir) {
  const html = path.join(dir, 'index.html');
  if (!existsSync(html)) return null;
  const text = readFileSync(html, 'utf8');
  const refs = [
    ...text.matchAll(/<script[^>]*\ssrc="([^"]+)"[^>]*>/g),
    ...text.matchAll(/<link[^>]*\srel="stylesheet"[^>]*href="([^"]+)"[^>]*>/g),
    ...text.matchAll(/<link[^>]*\shref="([^"]+)"[^>]*\srel="stylesheet"[^>]*>/g),
  ].map((m) => m[1]);
  return new Set(
    refs
      .filter((ref) => /^[^?#]*\.(js|mjs|css)(\?|#|$)/i.test(ref))
      .map((ref) => path.resolve(dir, ref.replace(/[?#].*$/, '').replace(/^\//, ''))),
  );
}

function measure(dir) {
  const entries = entryChunkFiles(dir);
  if (entries === null) {
    fail(`无法度量：${dir} 里没有 index.html（先跑一次 pnpm build，没有产物不算"体积达标"）`);
    return null;
  }
  const files = walk(dir)
    .filter((f) => ASSET_EXTS.has(path.extname(f)))
    .map((f) => {
      const buf = readFileSync(f);
      return { file: f, ...chunkOf(f), raw: buf.byteLength, gzip: gzipSync(buf, { level: GZIP_LEVEL }).byteLength };
    });
  if (files.length === 0) {
    fail(`无法度量：${dir} 里没有任何 js/css 产物（先跑一次 pnpm build）`);
    return null;
  }
  const buckets = Object.fromEntries(BUCKETS.map((b) => [b, { gzip: 0, raw: 0, files: 0 }]));
  for (const f of files) {
    f.bucket = entries.has(f.file)
      ? 'entry'
      : (VENDOR_BUCKETS.find((b) => f.chunk === b || f.chunk.startsWith(`${b}-`)) ?? 'app');
    for (const key of [f.bucket, 'total']) {
      buckets[key].gzip += f.gzip;
      buckets[key].raw += f.raw;
      buckets[key].files += 1;
    }
  }
  return { files, buckets };
}

function gitRevision() {
  try {
    return execSync('git rev-parse --short HEAD', { cwd: FRONTEND_ROOT, stdio: ['ignore', 'pipe', 'ignore'] })
      .toString()
      .trim();
  } catch {
    return 'unknown';
  }
}

function readBudget() {
  if (!existsSync(BUDGET_FILE)) return null;
  return JSON.parse(readFileSync(BUDGET_FILE, 'utf8'));
}

function header() {
  return [
    'gzip 体积预算：由 node scripts/check-bundle-budget.mjs --update 从一次真实 pnpm build 写入，禁止手改。',
    `口径：zlib level ${GZIP_LEVEL}，只统计 dist 下的 js/mjs/css；预算 = 写入时现值 x ${HEADROOM}（留 2% 重构余量）。`,
    '桶：entry=dist/index.html 直接引用的 chunk，vendor-antd/vendor-echarts/vendor=manualChunks 三组，',
    '    app=其余（路由页与组件 chunk），total=以上全部之和。',
    '字段：maxGzipBytes=门禁上限（字节），measuredGzipBytes=写入时的真实值（仅备查，不参与裁决）。',
    '改 vite.config.ts 的 manualChunks 分桶名时，必须同步改 check-bundle-budget.mjs 的 VENDOR_BUCKETS。',
  ];
}

function update(buckets) {
  const previous = readBudget();
  const payload = {
    $comment: header(),
    sourceRevision: gitRevision(),
    measuredAt: new Date().toISOString().replace(/\.\d{3}Z$/, 'Z'),
    gzipLevel: GZIP_LEVEL,
    headroom: HEADROOM,
    buckets: Object.fromEntries(
      BUCKETS.map((b) => [
        b,
        {
          maxGzipBytes: Math.ceil(buckets[b].gzip * HEADROOM),
          measuredGzipBytes: buckets[b].gzip,
          files: buckets[b].files,
        },
      ]),
    ),
  };
  if (previous?.vendorBuckets) payload.vendorBuckets = previous.vendorBuckets;
  writeFileSync(BUDGET_FILE, `${JSON.stringify(payload, null, 2)}\n`, 'utf8');
  console.log(`已写入预算：${BUDGET_FILE}`);
  for (const b of BUCKETS) {
    console.log(
      `  ${b.padEnd(14)} 现值 ${kb(buckets[b].gzip).padStart(9)} KiB -> 预算 ${kb(payload.buckets[b].maxGzipBytes).padStart(9)} KiB（${buckets[b].files} 个文件）`,
    );
  }
}

function check(buckets) {
  const budget = readBudget();
  if (!budget || !budget.buckets) {
    fail(`预算文件缺失或没有条目：${BUDGET_FILE}（先跑一次 pnpm build 再执行 --update）`);
    return;
  }
  for (const b of BUCKETS) {
    const entry = budget.buckets[b];
    if (!entry || typeof entry.maxGzipBytes !== 'number') {
      fail(`预算缺 ${b} 条目：${BUDGET_FILE}（跑一次 --update 生成）`);
      continue;
    }
    const actual = buckets[b].gzip;
    const over = actual > entry.maxGzipBytes;
    if (over) fail(`${b} gzip ${kb(actual)} KiB 超出预算 ${kb(entry.maxGzipBytes)} KiB（+${kb(actual - entry.maxGzipBytes)} KiB）`);
    console.log(
      `${b.padEnd(14)} gzip ${kb(actual).padStart(9)} KiB / 预算 ${kb(entry.maxGzipBytes).padStart(9)} KiB  ${over ? 'FAIL' : 'ok'}`,
    );
  }
  if (process.exitCode !== 1) console.log(`体积预算门禁通过（预算文件：${BUDGET_FILE}）`);
}

function report(result) {
  const buckets = result.buckets;
  const rows = [...result.files].sort((a, b) => b.gzip - a.gzip);
  console.log(`dist: ${distDir}`);
  console.log('chunk\tbucket\traw_KiB\tgzip_KiB\tfile');
  for (const f of rows) {
    console.log(`${f.chunk}\t${f.bucket}\t${kb(f.raw)}\t${kb(f.gzip)}\t${path.relative(distDir, f.file)}`);
  }
  console.log(`TOTAL\t-\t${kb(buckets.total.raw)}\t${kb(buckets.total.gzip)}\t${rows.length} files`);
}

const measured = measure(distDir);
if (measured) {
  if (MODE === 'update') update(measured.buckets);
  else if (MODE === 'report') report(measured);
  else check(measured.buckets);
}
if (process.exitCode === 1) process.exit(1);
