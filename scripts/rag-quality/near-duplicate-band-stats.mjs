#!/usr/bin/env node
// 近重复三档落带统计（reserve-sweep-1 S3 任务 3.1 / image-near-duplicate-bands v1 §2 的脚本化复算）。
//
// 口径与 ImageSimilarityGate（Java）逐行对齐：
//   floor = min(NEAR_DUPLICATE_FLOOR = 0.9887, threshold)            // effectiveFloor(threshold)
//   b1    = floor + Δ/3    （低/中边界，边界值归上一档：>=）
//   b2    = floor + 2Δ/3   （中/高边界）
//   tier  : cosine >= threshold → SAME_IMAGE；cosine >= floor → NEAR_DUPLICATE；否则 CROSS_IMAGE
//   band  : 只在 [floor, threshold) 内有值（NEAR_HIGH / NEAR_MID / NEAR_LOW）
//
// 数据源 = 定标锚 docs/rag-quality/image-threshold-calibration-v1.json 的 pairs（只读，本脚本不写任何文件，
// 不改权重常量、不改锚 JSON —— 权重与锚的改动只能由 ImageThresholdCalibrationIT 真跑重写）。
// 判据   = 必须复现 image-near-duplicate-bands-v1.md §2 的落带数字 18 / 0 / 0 / 1 / 71，不一致即非零退出。
//
// 用法：
//   node scripts/rag-quality/near-duplicate-band-stats.mjs
//   node scripts/rag-quality/near-duplicate-band-stats.mjs --threshold 0.9944
//
// 退出码：0 = 与 v1 落带基线逐数一致；1 = 不一致或数据源不可读。

import { readFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const REPO_ROOT = resolve(HERE, '..', '..');
const ANCHOR_PATH = join(REPO_ROOT, 'docs', 'rag-quality', 'image-threshold-calibration-v1.json');

// 与 ImageSimilarityGate 同值（只读复算用；改这些常量必须另立卡并重跑真跑，本脚本无权改）。
const GATE_DEFAULT_THRESHOLD = 0.9944;
const GATE_NEAR_DUPLICATE_FLOOR = 0.9887;

// v1 文档 §2 落带基线（= image-near-duplicate-bands-v1.md「90 对落带分布」）。
const EXPECTED_BAND_COUNTS = {
  SAME_IMAGE: 18,
  NEAR_HIGH: 0,
  NEAR_MID: 0,
  NEAR_LOW: 1,
  CROSS_IMAGE: 71,
};
const BAND_ORDER = ['SAME_IMAGE', 'NEAR_HIGH', 'NEAR_MID', 'NEAR_LOW', 'CROSS_IMAGE'];

// 同一份 v1 文档的按语料拆分（§2 第二张表）：real-pdf 9/0/0/1/35、programmatic 9/0/0/0/36。
const EXPECTED_PER_CORPUS = {
  'real-pdf': { SAME_IMAGE: 9, NEAR_HIGH: 0, NEAR_MID: 0, NEAR_LOW: 1, CROSS_IMAGE: 35 },
  programmatic: { SAME_IMAGE: 9, NEAR_HIGH: 0, NEAR_MID: 0, NEAR_LOW: 0, CROSS_IMAGE: 36 },
};

/** 解析 --threshold（缺省 = ImageSimilarityGate.DEFAULT_THRESHOLD 的展示口径 0.9944）。 */
function parseThreshold(argv) {
  const index = argv.indexOf('--threshold');
  if (index < 0) return GATE_DEFAULT_THRESHOLD;
  const raw = argv[index + 1];
  const value = Number(raw);
  if (raw === undefined || !Number.isFinite(value) || value <= 0 || value > 1) {
    throw new Error(`--threshold 取值非法：${String(raw)}（要求 0 < threshold <= 1）`);
  }
  return value;
}

/** 生效下限：阈值低于锚下限时随阈值收敛（与 ImageSimilarityGate.effectiveFloor 同式）。 */
const effectiveFloor = (threshold) => Math.min(GATE_NEAR_DUPLICATE_FLOOR, threshold);

/** 低/中档边界 = floor + Δ/3（含）。 */
const lowBandUpperBound = (threshold) => {
  const floor = effectiveFloor(threshold);
  return floor + (threshold - floor) / 3;
};

/** 中/高档边界 = floor + 2Δ/3（含）。 */
const highBandLowerBound = (threshold) => {
  const floor = effectiveFloor(threshold);
  return floor + (2 * (threshold - floor)) / 3;
};

/** 近似带落带（只在 [floor, threshold) 内返回 band，区间外返回 null）。 */
function bandOf(cosine, threshold) {
  const floor = effectiveFloor(threshold);
  if (cosine < floor || cosine >= threshold) return null;
  if (cosine >= highBandLowerBound(threshold)) return 'NEAR_HIGH';
  if (cosine >= lowBandUpperBound(threshold)) return 'NEAR_MID';
  return 'NEAR_LOW';
}

/** 五档落带标签（同图 / 三档近似 / 异图）。 */
function labelOf(cosine, threshold) {
  if (cosine >= threshold) return 'SAME_IMAGE';
  const band = bandOf(cosine, threshold);
  return band ?? 'CROSS_IMAGE';
}

/** 线性插值分位（与常见统计口径一致；n 很小时如实退化）。 */
function quantile(sorted, p) {
  if (sorted.length === 0) return null;
  if (sorted.length === 1) return sorted[0];
  const position = (sorted.length - 1) * p;
  const lower = Math.floor(position);
  const upper = Math.ceil(position);
  const weight = position - lower;
  return sorted[lower] * (1 - weight) + sorted[upper] * weight;
}

/** 一组余弦值的分位摘要（n / min / p25 / median / p75 / max）。 */
function summarize(values) {
  if (values.length === 0) return null;
  const sorted = [...values].sort((a, b) => a - b);
  return {
    n: sorted.length,
    min: sorted[0],
    p25: quantile(sorted, 0.25),
    median: quantile(sorted, 0.5),
    p75: quantile(sorted, 0.75),
    max: sorted[sorted.length - 1],
  };
}

const fixed = (value) => (value === null ? '—' : value.toFixed(4));

/** 逐行打印带对齐的文本表（不引第三方依赖）。 */
function printTable(headers, rows) {
  const widths = headers.map((header, column) =>
    Math.max(header.length, ...rows.map((row) => [...String(row[column])].length)),
  );
  const line = (cells) =>
    cells.map((cell, column) => String(cell).padEnd(widths[column])).join(' | ');
  console.log(line(headers));
  console.log(widths.map((width) => '-'.repeat(width)).join('-|-'));
  for (const row of rows) console.log(line(row));
}

function main() {
  const threshold = parseThreshold(process.argv.slice(2));
  const anchor = JSON.parse(readFileSync(ANCHOR_PATH, 'utf8'));
  const pairs = Array.isArray(anchor.pairs) ? anchor.pairs : [];
  if (pairs.length === 0) {
    console.error(`[FAIL] 锚文件没有 pairs：${ANCHOR_PATH}`);
    process.exit(1);
  }

  const bogus = pairs.filter((pair) => !Number.isFinite(pair.cosine));
  if (bogus.length > 0) {
    console.error(`[FAIL] ${bogus.length} 条 pair 的 cosine 不是有限数，拒绝统计`);
    process.exit(1);
  }

  const floor = effectiveFloor(threshold);
  const b1 = lowBandUpperBound(threshold);
  const b2 = highBandLowerBound(threshold);
  const rawThreshold = Number(anchor.threshold?.recommendedThreshold);

  console.log('# 近重复三档落带统计（v1 锚复算，只读）');
  console.log(`锚文件：docs/rag-quality/image-threshold-calibration-v1.json（${anchor.calibrationVersion ?? '未知版本'}）`);
  console.log(`锚生成时间：${anchor.generatedAt ?? '未知'}`);
  console.log(`样本对：${pairs.length}`);
  console.log(`阈值：${threshold}（ImageSimilarityGate.DEFAULT_THRESHOLD；锚原值 ${Number.isFinite(rawThreshold) ? rawThreshold : '缺失'}）`);
  console.log(`下限：${floor}  Δ=${(threshold - floor).toFixed(4)}  b1(低/中)=${b1.toFixed(4)}  b2(中/高)=${b2.toFixed(4)}`);
  console.log('');

  const byBand = new Map(BAND_ORDER.map((band) => [band, []]));
  const byCorpus = new Map();
  for (const pair of pairs) {
    const label = labelOf(pair.cosine, threshold);
    byBand.get(label).push(pair);
    if (!byCorpus.has(pair.corpus)) {
      byCorpus.set(pair.corpus, new Map(BAND_ORDER.map((band) => [band, 0])));
    }
    const counts = byCorpus.get(pair.corpus);
    counts.set(label, counts.get(label) + 1);
  }

  console.log('## 1. 落带分布');
  const bandRows = BAND_ORDER.map((band) => {
    const list = byBand.get(band);
    const share = ((list.length / pairs.length) * 100).toFixed(1);
    let range = '[0, floor)';
    if (band === 'SAME_IMAGE') range = `[${threshold}, 1]`;
    if (band === 'NEAR_HIGH') range = `[${b2.toFixed(4)}, ${threshold})`;
    if (band === 'NEAR_MID') range = `[${b1.toFixed(4)}, ${b2.toFixed(4)})`;
    if (band === 'NEAR_LOW') range = `[${floor}, ${b1.toFixed(4)})`;
    return [band, range, list.length, `${share}%`];
  });
  printTable(['带', '区间', '对数', '占比'], bandRows);
  console.log('');

  console.log('## 2. 按语料拆分');
  const corpusNames = [...byCorpus.keys()].sort();
  printTable(
    ['语料', ...BAND_ORDER, '合计'],
    corpusNames.map((corpus) => {
      const counts = byCorpus.get(corpus);
      const total = BAND_ORDER.reduce((sum, band) => sum + counts.get(band), 0);
      return [corpus, ...BAND_ORDER.map((band) => counts.get(band)), total];
    }),
  );
  console.log('');

  console.log('## 3. 各带分位（n 太小仅存档，不作推断依据）');
  printTable(
    ['带', 'n', 'min', 'p25', 'median', 'p75', 'max'],
    BAND_ORDER.filter((band) => byBand.get(band).length > 0).map((band) => {
      const stat = summarize(byBand.get(band).map((pair) => pair.cosine));
      return [
        band,
        stat.n,
        fixed(stat.min),
        fixed(stat.p25),
        fixed(stat.median),
        fixed(stat.p75),
        fixed(stat.max),
      ];
    }),
  );
  console.log('');

  console.log('## 4. 跨带极值（近似带是否被真实样本填充的直接证据）');
  const belowFloor = byBand
    .get('CROSS_IMAGE')
    .slice()
    .sort((left, right) => right.cosine - left.cosine)[0];
  const sameMin = byBand
    .get('SAME_IMAGE')
    .slice()
    .sort((left, right) => left.cosine - right.cosine)[0];
  console.log(
    `floor 以下最高余弦：${belowFloor === undefined ? '无' : `${belowFloor.cosine}（${belowFloor.corpus}: ${belowFloor.leftKey} ↔ ${belowFloor.rightKey}）`}`,
  );
  console.log(
    `SAME_IMAGE 最低余弦：${sameMin === undefined ? '无' : `${sameMin.cosine}（${sameMin.corpus}: ${sameMin.leftKey} ↔ ${sameMin.rightKey}）`}`,
  );
  for (const band of ['NEAR_HIGH', 'NEAR_MID', 'NEAR_LOW']) {
    const list = byBand.get(band);
    if (list.length === 0) {
      console.log(`${band}：零样本（该带权重未在本锚上校准）`);
      continue;
    }
    for (const pair of list) {
      console.log(
        `${band}：${pair.cosine}（${pair.corpus}: ${pair.leftKey} ↔ ${pair.rightKey}，真实分层 ${pair.stratum ?? '未知'}）`,
      );
    }
  }
  console.log('');

  // ---- 判定：必须逐数复现 v1 文档 ----
  let failed = false;
  console.log('## 5. 与 v1 文档逐数核对');
  const mismatches = [];
  for (const band of BAND_ORDER) {
    const actual = byBand.get(band).length;
    const expected = EXPECTED_BAND_COUNTS[band];
    if (actual !== expected) {
      mismatches.push(`${band}: 实际 ${actual} ≠ 基线 ${expected}`);
    }
  }
  if (mismatches.length > 0) {
    failed = true;
    console.error(`[FAIL] 落带与 v1 文档不一致（阈值 ${threshold}）：`);
    for (const message of mismatches) console.error(`  - ${message}`);
  } else {
    console.log(
      `落带一致：SAME_IMAGE ${EXPECTED_BAND_COUNTS.SAME_IMAGE} / NEAR_HIGH ${EXPECTED_BAND_COUNTS.NEAR_HIGH} / NEAR_MID ${EXPECTED_BAND_COUNTS.NEAR_MID} / NEAR_LOW ${EXPECTED_BAND_COUNTS.NEAR_LOW} / CROSS_IMAGE ${EXPECTED_BAND_COUNTS.CROSS_IMAGE}`,
    );
  }
  for (const [corpus, expected] of Object.entries(EXPECTED_PER_CORPUS)) {
    const counts = byCorpus.get(corpus);
    if (counts === undefined) {
      failed = true;
      console.error(`[FAIL] 缺语料 ${corpus}（锚的语料结构与 v1 文档不一致）`);
      continue;
    }
    for (const band of BAND_ORDER) {
      if (counts.get(band) !== expected[band]) {
        failed = true;
        console.error(`[FAIL] ${corpus}/${band}: 实际 ${counts.get(band)} ≠ 基线 ${expected[band]}`);
      }
    }
  }
  if (Number.isFinite(rawThreshold) && rawThreshold !== threshold) {
    const rawCounts = BAND_ORDER.map((band) => [
      band,
      pairs.filter((pair) => labelOf(pair.cosine, rawThreshold) === band).length,
    ]);
    const sameBands = rawCounts.every(([band, count]) => count === EXPECTED_BAND_COUNTS[band]);
    console.log(
      `锚原值阈值 ${rawThreshold} 复算落带一致：${sameBands ? '是' : '否'}（原值仅作交叉验证，不参与判定）`,
    );
  }

  if (failed) {
    console.error('结果：与 v1 文档不一致，非零退出（EXIT=1）');
    process.exit(1);
  }
  console.log('结果：复现 v1 落带 18/0/0/1/71 ✓（EXIT=0）');
}

main();
