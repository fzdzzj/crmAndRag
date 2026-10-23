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
//   node scripts/rag-quality/near-duplicate-band-stats.mjs --anchor docs/rag-quality/image-threshold-calibration-v2.json --expect S,N_H,N_M,N_L,C
//
// 退出码：0 = 与 v1 落带基线逐数一致（无参数路径）/ 与 v2 锚复算及 --expect 一致（--anchor 路径）；
//         1 = 数字不一致（含语料口径不自洽）；2 = 用法错误（给了 --anchor 却没给 --expect）。
//
// --anchor 路径（calibrate-near-dup-bands v2）：从锚的 pairs 重算双视图落带、分位、按 kind 单调性、判定树 F0–F5，
// 另外核对语料口径三项（corpus.baseDetail 条数 = bases、corpus.excludedBases 条数 = excludedBaseCount、
// corpus.imageEmbeddingCalls = images×2）与 candidateFloor 的 F1 门控（F1 未命中 ⇒ 锚里必须是 null，脚本也复算为 null）。

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

// ==================== v2：--anchor / --expect（calibrate-near-dup-bands 任务 4.1） ====================
//
// 与不带参数路径的关系：**不带任何参数时行为逐字不变**——v2Main 只在出现 --anchor 时才被调用，上面那条 v1 路径一行未改，
// 仍打印 18/0/0/1/71 且 EXIT=0（硬边界 B5）。
//
// 带 --anchor 时：只吃锚的 `pairs` 原始样本对，**逐数复算**落带、分位、带内精度、单调性、候选下限与判定树，
// 再与锚内已存块交叉校验，因此 image-near-duplicate-bands-v2.md 的每个数字都能用这条命令复现（不许手算、不许手改）。
//
// 用法：
//   node scripts/rag-quality/near-duplicate-band-stats.mjs \
//     --anchor docs/rag-quality/image-threshold-calibration-v2.json --expect 193,3,1,3,340
//
// 退出码：0 = 复算与 --expect 一致且与锚内已存块一致；1 = 不一致或数据源不可读；2 = 用法错误（--anchor 缺 --expect 等）。

const V2_BANDS = ['SAME_IMAGE', 'NEAR_HIGH', 'NEAR_MID', 'NEAR_LOW', 'CROSS_IMAGE'];
const V2_NEAR_BANDS = ['NEAR_HIGH', 'NEAR_MID', 'NEAR_LOW'];
const V2_STRATA = ['same-image', 'near-duplicate', 'same-class', 'cross-class'];
// 判定树门槛（openspec/changes/calibrate-near-dup-bands/proposal.md §4 写死，跑后不改口径）。
const V2_MIN_BAND_SAMPLES = 30;
const V2_MIN_NEAR_PRECISION = 0.5;
const V2_UNREACHABLE_SHARE = 0.5;

const V2_VIEW_BANDS = 'bands';

/** 解析 --anchor / --expect；缺 --expect 即用法错误（不许静默通过）。 */
function parseAnchorArgs(argv) {
  const anchorIndex = argv.indexOf('--anchor');
  const expectIndex = argv.indexOf('--expect');
  if (anchorIndex < 0) {
    throw new Error('--expect 必须与 --anchor 一起使用（复算 v2 锚）');
  }
  const anchorPath = argv[anchorIndex + 1];
  if (anchorPath === undefined || anchorPath.startsWith('--')) {
    throw new Error('--anchor 缺少锚文件路径');
  }
  if (expectIndex < 0) {
    throw new Error('给了 --anchor 却没给 --expect <SAME_IMAGE,NEAR_HIGH,NEAR_MID,NEAR_LOW,CROSS_IMAGE>');
  }
  const raw = argv[expectIndex + 1];
  if (raw === undefined || raw.startsWith('--')) {
    throw new Error('--expect 缺少取值（5 个整数，逗号分隔）');
  }
  const parts = raw.split(',').map((part) => part.trim());
  if (parts.length !== 5) {
    throw new Error(`--expect 需要 5 个数，实际 ${parts.length} 个：${raw}`);
  }
  const values = parts.map((part) => Number(part));
  if (values.some((value) => !Number.isInteger(value) || value < 0)) {
    throw new Error(`--expect 取值非法（要求非负整数）：${raw}`);
  }
  const expected = {};
  V2_BANDS.forEach((band, index) => {
    expected[band] = values[index];
  });
  return { anchorPath, expected };
}

/** 通用落带（floor 由调用方给：生产视图用闸门常量，锚视图用实测异图上限）。 */
function v2BandOf(cosine, floor, threshold) {
  if (cosine < floor || cosine >= threshold) return null;
  if (cosine >= floor + (2 * (threshold - floor)) / 3) return 'NEAR_HIGH';
  if (cosine >= floor + (threshold - floor) / 3) return 'NEAR_MID';
  return 'NEAR_LOW';
}

/** 五档标签（与 NearDuplicateBandAnalyzer.labelOf 同式）。 */
function v2LabelOf(cosine, floor, threshold) {
  if (cosine >= threshold) return 'SAME_IMAGE';
  return v2BandOf(cosine, floor, threshold) ?? 'CROSS_IMAGE';
}

/** 分位摘要（null 而非 0：空表不许装成"全 0"）。 */
function v2Stats(values) {
  if (values.length === 0) {
    return {
      count: 0,
      min: null,
      p05: null,
      p25: null,
      median: null,
      p75: null,
      p95: null,
      max: null,
      mean: null,
    };
  }
  const sorted = [...values].sort((a, b) => a - b);
  let sum = 0;
  for (const value of sorted) sum += value;
  return {
    count: sorted.length,
    min: sorted[0],
    p05: quantile(sorted, 0.05),
    p25: quantile(sorted, 0.25),
    median: quantile(sorted, 0.5),
    p75: quantile(sorted, 0.75),
    p95: quantile(sorted, 0.95),
    max: sorted[sorted.length - 1],
    mean: sum / sorted.length,
  };
}

/** 单视图落带（threshold 为 null 时不给落带表：不可分就不硬凑）。 */
function v2View(name, floor, threshold, pairs) {
  const byBand = new Map(V2_BANDS.map((band) => [band, []]));
  let belowFloor = 0;
  let aboveThreshold = 0;
  let nearDuplicateCount = 0;
  let nearDuplicateBelowFloor = 0;
  for (const pair of pairs) {
    const isNear = pair.stratum === 'near-duplicate';
    if (isNear) nearDuplicateCount += 1;
    if (pair.cosine < floor) {
      belowFloor += 1;
      if (isNear) nearDuplicateBelowFloor += 1;
    }
    if (threshold !== null) {
      if (pair.cosine >= threshold) aboveThreshold += 1;
      byBand.get(v2LabelOf(pair.cosine, floor, threshold)).push(pair);
    }
  }
  const bands = V2_BANDS.map((band) => {
    const list = byBand.get(band);
    const near = list.filter((pair) => pair.stratum === 'near-duplicate').length;
    return {
      band,
      count: list.length,
      nearDuplicateCount: near,
      share: list.length === 0 ? null : near / list.length,
      stats: v2Stats(list.map((pair) => pair.cosine)),
    };
  });
  return {
    name,
    floor,
    threshold,
    separable: threshold !== null,
    pairCount: pairs.length,
    bands,
    belowFloor,
    aboveThreshold,
    nearDuplicateCount,
    nearDuplicateBelowFloor,
    nearDuplicateBelowFloorShare:
      nearDuplicateCount === 0 ? null : nearDuplicateBelowFloor / nearDuplicateCount,
  };
}

/** 异图上限 = max(same-class.max, cross-class.max)（空档忽略）。 */
function v2CrossImageCeiling(strata) {
  let ceiling = null;
  for (const name of ['same-class', 'cross-class']) {
    const max = strata[name].max;
    if (max !== null && (ceiling === null || max > ceiling)) ceiling = max;
  }
  return ceiling;
}

/** 锚视图：floor = 实测异图上限；threshold = 其与同图下限的中点；不可分则阈值 null。 */
function v2AnchorView(pairs, strata) {
  const sameImageFloor = strata['same-image'].min;
  const crossImageCeiling = v2CrossImageCeiling(strata);
  const floor = crossImageCeiling === null ? GATE_NEAR_DUPLICATE_FLOOR : crossImageCeiling;
  const separable =
    sameImageFloor !== null && crossImageCeiling !== null && crossImageCeiling < sameImageFloor;
  const threshold = separable ? (crossImageCeiling + sameImageFloor) / 2 : null;
  return v2View('anchorView', floor, threshold, pairs);
}

/** 判定树 F0–F5 照判（条件原文与 proposal §4 一致；任何分支都不改常量）。 */
function v2Judge(production, anchor, sameImageFloor, crossImageCeiling) {
  const bandOfView = (view, band) => view.bands.find((entry) => entry.band === band);
  const statsOf = (view) => V2_NEAR_BANDS.map((band) => bandOfView(view, band));
  const describe = (bands) =>
    bands
      .map(
        (entry) =>
          `${entry.band}=${entry.count}(近重复占比 ${entry.share === null ? '—' : entry.share.toFixed(4)})`,
      )
      .join(' ');
  const near = statsOf(production);
  const anchorNear = statsOf(anchor);

  const allEnough = near.every((entry) => entry.count >= V2_MIN_BAND_SAMPLES);
  const allPrecise = near.every(
    (entry) => entry.share !== null && entry.share >= V2_MIN_NEAR_PRECISION,
  );
  const belowShare = production.nearDuplicateBelowFloorShare;
  const unreachable = belowShare !== null && belowShare > V2_UNREACHABLE_SHARE;
  const inseparable =
    sameImageFloor !== null && crossImageCeiling !== null && crossImageCeiling >= sameImageFloor;
  const impure = near.filter(
    (entry) =>
      entry.count >= V2_MIN_BAND_SAMPLES &&
      (entry.share === null || entry.share < V2_MIN_NEAR_PRECISION),
  );
  const thin = near.filter((entry) => entry.count < V2_MIN_BAND_SAMPLES);
  const anchorWider = V2_NEAR_BANDS.every(
    (band) => bandOfView(anchor, band).count > bandOfView(production, band).count,
  );

  return [
    {
      code: 'F0',
      condition: '每带 NEAR_HIGH/NEAR_MID/NEAR_LOW >= 30 对且带内近重复占比 >= 0.5',
      hit: allEnough && allPrecise,
      evidence: `productionView ${describe(near)}（门槛 30 对 / 0.5）`,
    },
    {
      code: 'F1',
      condition: 'nearDuplicate 对中余弦 < 0.9887 的比例 > 0.5',
      hit: unreachable,
      evidence:
        `productionView.nearDuplicateBelowFloor=${production.nearDuplicateBelowFloor}/` +
        `${production.nearDuplicateCount}（占比 ${belowShare === null ? '—' : belowShare.toFixed(4)}，门槛 0.5）`,
    },
    {
      code: 'F2',
      condition: 'anchorView 不可分（crossImageCeiling >= sameImageFloor）',
      hit: inseparable,
      evidence:
        `strata["same-image"].min=${sameImageFloor}，crossImageCeiling=${crossImageCeiling}，` +
        `anchorView.separable=${anchor.separable}`,
    },
    {
      code: 'F3',
      condition: '某带 >= 30 对但带内近重复占比 < 0.5',
      hit: impure.length > 0,
      evidence:
        impure.length === 0
          ? `无「样本足但带内以异图为主」的带：${describe(near)}`
          : `命中带：${describe(impure)}`,
    },
    {
      code: 'F4',
      condition: '任一近似带 < 30 对',
      hit: thin.length > 0,
      evidence: thin.length === 0 ? `三带均 >= 30 对：${describe(near)}` : `样本不足带：${describe(thin)}`,
    },
    {
      code: 'F5',
      condition: 'anchorView 每带样本数 > productionView 同带样本数，且 F1 未触发',
      hit: anchorWider && !unreachable,
      evidence:
        `productionView ${describe(near)} vs anchorView ${describe(anchorNear)}` +
        `（F1 命中=${unreachable}）`,
    },
  ];
}

/** 落带表（带 / 区间 / 对数 / 占比 / 近重复对 / 带内精度）。 */
function v2PrintBands(view) {
  if (!view.separable) {
    console.log(
      '不可分：crossImageCeiling >= sameImageFloor，本视图不给阈值与落带表（不硬凑，见 F2）',
    );
    return;
  }
  const floor = view.floor;
  const threshold = view.threshold;
  const b1 = floor + (threshold - floor) / 3;
  const b2 = floor + (2 * (threshold - floor)) / 3;
  const rows = V2_BANDS.map((band) => {
    const entry = view.bands.find((candidate) => candidate.band === band);
    let range = '[0, floor)';
    if (band === 'SAME_IMAGE') range = `[${threshold}, 1]`;
    if (band === 'NEAR_HIGH') range = `[${b2.toFixed(4)}, ${Number(threshold).toFixed(4)})`;
    if (band === 'NEAR_MID') range = `[${b1.toFixed(4)}, ${b2.toFixed(4)})`;
    if (band === 'NEAR_LOW') range = `[${Number(floor).toFixed(4)}, ${b1.toFixed(4)})`;
    const share = view.pairCount === 0 ? '0.0%' : `${((entry.count / view.pairCount) * 100).toFixed(1)}%`;
    const precision = entry.share === null ? '—' : entry.share.toFixed(4);
    return [band, range, entry.count, share, entry.nearDuplicateCount, precision];
  });
  printTable(['带', '区间', '对数', '占比', '近重复对', '带内精度'], rows);
  console.log(
    `floor=${floor}  threshold=${threshold}  Δ=${(threshold - floor).toFixed(4)}  ` +
      `b1(低/中)=${b1.toFixed(4)}  b2(中/高)=${b2.toFixed(4)}`,
  );
}

/** 各带分位表。 */
function v2PrintQuantiles(view) {
  if (!view.separable) {
    console.log('不可分：无落带，分位表不适用');
    return;
  }
  const rows = view.bands
    .filter((entry) => entry.count > 0)
    .map((entry) => [
      entry.band,
      entry.count,
      fixed(entry.stats.min),
      fixed(entry.stats.p05),
      fixed(entry.stats.p25),
      fixed(entry.stats.median),
      fixed(entry.stats.p75),
      fixed(entry.stats.p95),
      fixed(entry.stats.max),
    ]);
  printTable(['带', 'n', 'min', 'p05', 'p25', 'median', 'p75', 'p95', 'max'], rows);
}

/** v2 主流程：复算 → 打印 → 交叉校验 → --expect 核对。 */
function v2Main(argv) {
  let parsed;
  try {
    parsed = parseAnchorArgs(argv);
  } catch (error) {
    console.error(`[FAIL] 用法错误：${error.message}`);
    console.error(
      '用法：node scripts/rag-quality/near-duplicate-band-stats.mjs ' +
        '--anchor docs/rag-quality/image-threshold-calibration-v2.json --expect S,N_H,N_M,N_L,C',
    );
    process.exit(2);
  }
  const { anchorPath, expected } = parsed;
  const resolved = resolve(REPO_ROOT, anchorPath);
  let anchor;
  try {
    anchor = JSON.parse(readFileSync(resolved, 'utf8'));
  } catch (error) {
    console.error(`[FAIL] 锚文件不可读：${anchorPath}（${error.message}）`);
    process.exit(1);
  }
  const pairs = Array.isArray(anchor.pairs) ? anchor.pairs : [];
  if (pairs.length === 0) {
    console.error(`[FAIL] 锚文件没有 pairs：${anchorPath}`);
    process.exit(1);
  }
  const bogus = pairs.filter((pair) => !Number.isFinite(pair.cosine));
  if (bogus.length > 0) {
    console.error(`[FAIL] ${bogus.length} 条 pair 的 cosine 不是有限数，拒绝统计`);
    process.exit(1);
  }

  const corpus = anchor.corpus ?? {};
  const strata = {};
  for (const name of V2_STRATA) {
    strata[name] = v2Stats(
      pairs.filter((pair) => pair.stratum === name).map((pair) => pair.cosine),
    );
  }
  const sameImageFloor = strata['same-image'].min;
  const crossImageCeiling = v2CrossImageCeiling(strata);
  const production = v2View('productionView', GATE_NEAR_DUPLICATE_FLOOR, GATE_DEFAULT_THRESHOLD, pairs);
  const anchorView = v2AnchorView(pairs, strata);
  const kinds =
    Array.isArray(corpus.kinds) && corpus.kinds.length > 0
      ? corpus.kinds
      : ['jpeg-q80', 'scale-085', 'scale-070', 'crop-04', 'crop-12'];
  const byKind = kinds.map((kind) => {
    const values = pairs
      .filter((pair) => pair.stratum === 'near-duplicate' && pair.kind === kind)
      .map((pair) => pair.cosine);
    return { kind, stats: v2Stats(values) };
  });
  const medians = byKind.map((entry) => entry.stats.median);
  const inversions = [];
  for (let index = 1; index < medians.length; index += 1) {
    const previous = medians[index - 1];
    const current = medians[index];
    if (previous !== null && current !== null && current > previous) {
      inversions.push({
        leftKind: kinds[index - 1],
        rightKind: kinds[index],
        leftMedian: previous,
        rightMedian: current,
      });
    }
  }
  const monotonic = inversions.length === 0;
  const judgements = v2Judge(production, anchorView, sameImageFloor, crossImageCeiling);
  // candidateFloor 的处置写在 proposal §9.5：只有 F1 命中才给候选下限；否则一律 null，
  // 不允许把「F1 未命中时算出的 p05」当成下调 floor 的建议（首轮 0.4199 就是这样被误读的）。
  const nearDuplicateP05 = strata['near-duplicate'].p05;
  const f1Judgement = judgements.find((entry) => entry.code === 'F1');
  const candidateFloor = f1Judgement.hit ? nearDuplicateP05 : null;

  console.log('# 近重复三档落带统计（v2 锚复算，只读）');
  console.log(`锚文件：${anchorPath}（${anchor.calibrationVersion ?? '未知版本'}）`);
  console.log(`锚生成时间：${anchor.generatedAt ?? '未知'}`);
  console.log(
    `语料：bases=${corpus.bases ?? '缺失'} variants=${corpus.variants ?? '缺失'} ` +
      `images=${corpus.images ?? '缺失'} skips=${corpus.skips ?? '缺失'} ` +
      `imageEmbeddingCalls=${corpus.imageEmbeddingCalls ?? '缺失'} elapsedMillis=${anchor.elapsedMillis ?? '缺失'} ` +
      `failedCalls=${anchor.failedCalls ?? '缺失'}`,
  );
  console.log(`样本对：${pairs.length}`);
  console.log('');

  console.log('## 1. 落带分布 · 生产视图（floor 0.9887 / threshold 0.9944，ImageSimilarityGate 现值）');
  v2PrintBands(production);
  console.log('');

  console.log('## 2. 落带分布 · 锚视图（floor = 实测异图上限，threshold = 其与同图下限的中点）');
  console.log(`实测：sameImageFloor=${sameImageFloor}  crossImageCeiling=${crossImageCeiling}`);
  v2PrintBands(anchorView);
  console.log('');

  console.log('## 3. stratum × band 交叉表（生产视图；v2 新增 near-duplicate 档）');
  printTable(
    ['stratum', ...V2_BANDS, '合计'],
    V2_STRATA.filter((name) => strata[name].count > 0).map((name) => {
      const counts = V2_BANDS.map(
        (band) =>
          pairs.filter(
            (pair) =>
              pair.stratum === name &&
              v2LabelOf(pair.cosine, production.floor, production.threshold) === band,
          ).length,
      );
      return [name, ...counts, counts.reduce((sum, value) => sum + value, 0)];
    }),
  );
  console.log('');

  console.log('## 4. 各带分位 · 生产视图');
  v2PrintQuantiles(production);
  console.log('');

  console.log('## 5. 各带分位 · 锚视图');
  v2PrintQuantiles(anchorView);
  console.log('');

  console.log('## 6. 档位总览（同图下限 / 异图上限 / 候选下限）');
  printTable(
    ['stratum', 'n', 'min', 'p05', 'median', 'max'],
    V2_STRATA.map((name) => [
      name,
      strata[name].count,
      fixed(strata[name].min),
      fixed(strata[name].p05),
      fixed(strata[name].median),
      fixed(strata[name].max),
    ]),
  );
  console.log(`candidateFloor = ${candidateFloor === null ? 'null' : candidateFloor}（F1 命中=${f1Judgement.hit}）`);
  console.log(
    `  near-duplicate.p05 = ${
      nearDuplicateP05 === null ? '缺失' : nearDuplicateP05
    }${candidateFloor === null ? '（F1 未命中 ⇒ 不给候选下限；p05 仅作分布参考，不是建议值）' : '（F1 命中 ⇒ 候选下限取此值，仍需另立卡决策，本锚不改任何常量）'}`,
  );
  console.log('');

  console.log('## 7. belowFloor / aboveThreshold（可达性口径）');
  printTable(
    ['视图', 'belowFloor', '占比', 'aboveThreshold', '占比', 'nearDup<floor', 'nearDup 占比'],
    [production, anchorView].map((view) => [
      view.name,
      view.belowFloor,
      `${((view.belowFloor / view.pairCount) * 100).toFixed(1)}%`,
      view.aboveThreshold,
      `${((view.aboveThreshold / view.pairCount) * 100).toFixed(1)}%`,
      `${view.nearDuplicateBelowFloor}/${view.nearDuplicateCount}`,
      view.nearDuplicateBelowFloorShare === null
        ? '—'
        : `${(view.nearDuplicateBelowFloorShare * 100).toFixed(1)}%`,
    ]),
  );
  console.log('');

  console.log('## 8. 按 kind 的强度单调性（jpeg-q80 < scale-085 < scale-070 < crop-04 < crop-12）');
  printTable(
    ['kind', 'n', 'min', 'p05', 'median', 'p75', 'max'],
    byKind.map((entry) => [
      entry.kind,
      entry.stats.count,
      fixed(entry.stats.min),
      fixed(entry.stats.p05),
      fixed(entry.stats.median),
      fixed(entry.stats.p75),
      fixed(entry.stats.max),
    ]),
  );
  console.log(
    `单调（中位数随强度非递增）：${monotonic ? '是' : `否，反序 ${inversions.length} 处`}`,
  );
  for (const inversion of inversions) {
    console.log(
      `  反序：${inversion.leftKind}(${fixed(inversion.leftMedian)}) → ` +
        `${inversion.rightKind}(${fixed(inversion.rightMedian)})`,
    );
  }
  console.log('');

  console.log('## 9. 判定树照判（proposal §4；任何分支都不改常量）');
  printTable(
    ['分支', '条件', '命中', '依据'],
    judgements.map((judgement) => [
      judgement.code,
      judgement.condition,
      judgement.hit ? '命中' : '未命中',
      judgement.evidence,
    ]),
  );
  console.log('');

  console.log('## 10. 语料口径（逐 base 白底合成像素属性 / 退化剔除登记）');
  const baseDetail = Array.isArray(corpus.baseDetail) ? corpus.baseDetail : [];
  const excludedBases = Array.isArray(corpus.excludedBases) ? corpus.excludedBases : [];
  const corpusMismatches = [];
  if (baseDetail.length !== (corpus.bases ?? 0)) {
    corpusMismatches.push(
      `corpus.baseDetail 条数 ${baseDetail.length} ≠ corpus.bases ${corpus.bases ?? '缺失'}`,
    );
  }
  for (const pixel of baseDetail) {
    if (!(pixel.uniqueColors > 1)) {
      corpusMismatches.push(`保留 base 的唯一色数不得 ≤ 1：${pixel.baseKey} = ${pixel.uniqueColors}`);
    }
  }
  if (excludedBases.length !== (corpus.excludedBaseCount ?? 0)) {
    corpusMismatches.push(
      `corpus.excludedBases 条数 ${excludedBases.length} ≠ excludedBaseCount ${corpus.excludedBaseCount ?? '缺失'}`,
    );
  }
  for (const excluded of excludedBases) {
    if (!(excluded.uniqueColors <= 1)) {
      corpusMismatches.push(`剔除理由必须是唯一色数 ≤ 1：${excluded.baseKey} = ${excluded.uniqueColors}`);
    }
  }
  const doubledCalls = (corpus.images ?? 0) * 2;
  if ((corpus.imageEmbeddingCalls ?? -1) !== doubledCalls) {
    corpusMismatches.push(
      `corpus.imageEmbeddingCalls ${corpus.imageEmbeddingCalls ?? '缺失'} ≠ images×2 ${doubledCalls}`,
    );
  }
  printTable(
    ['base', 'colorModel', 'hasAlpha', 'uniqueColors', 'mean', 'stdDev'],
    baseDetail.map((pixel) => [
      pixel.baseKey,
      pixel.colorModel,
      pixel.hasAlpha,
      pixel.uniqueColors,
      pixel.mean,
      pixel.stdDev,
    ]),
  );
  if (excludedBases.length === 0) {
    console.log('退化剔除：0 个（没有任何 base 在「白底合成后像素全同」）');
  } else {
    printTable(
      ['被剔除 base', 'uniqueColors', '原因'],
      excludedBases.map((excluded) => [excluded.baseKey, excluded.uniqueColors, excluded.reason]),
    );
  }
  console.log(
    `保留 base ${corpus.bases ?? '缺失'} 个、剔除 ${excludedBases.length} 个、图 ${corpus.images ?? '缺失'} 张、` +
      `外呼 ${corpus.imageEmbeddingCalls ?? '缺失'} 次（= images×2）`,
  );
  if (corpusMismatches.length === 0) {
    console.log('语料口径自洽（baseDetail / excludedBases / imageEmbeddingCalls 三项均与计数一致）');
  } else {
    console.error(`[FAIL] 语料口径不自洽 ${corpusMismatches.length} 处：`);
    for (const message of corpusMismatches) console.error(`  - ${message}`);
  }
  console.log('');

  // ---- 与锚内已存块交叉校验（锚是 IT 写的，脚本不许信它，必须复算对得上） ----
  const mismatches = [];
  const check = (label, actual, stored) => {
    if (stored !== undefined && !Object.is(actual, stored)) {
      mismatches.push(`${label}: 复算 ${actual} ≠ 锚内 ${stored}`);
    }
  };
  for (const band of V2_BANDS) {
    check(
      `productionView/${band}`,
      production.bands.find((entry) => entry.band === band).count,
      (anchor.productionView?.[V2_VIEW_BANDS] ?? []).find((entry) => entry.band === band)?.count,
    );
    check(
      `anchorView/${band}`,
      anchorView.bands.find((entry) => entry.band === band).count,
      (anchor.anchorView?.[V2_VIEW_BANDS] ?? []).find((entry) => entry.band === band)?.count,
    );
  }
  for (const name of V2_STRATA) {
    check(`strata/${name}.count`, strata[name].count, anchor.strata?.[name]?.count);
  }
  check('productionView.belowFloor', production.belowFloor, anchor.productionView?.belowFloor);
  check('anchorView.belowFloor', anchorView.belowFloor, anchor.anchorView?.belowFloor);
  check('anchorView.threshold', anchorView.threshold, anchor.anchorView?.threshold ?? null);
  check('candidateFloor', candidateFloor, anchor.candidateFloor ?? null);
  check('monotonicity.monotonic', monotonic, anchor.monotonicity?.monotonic);
  for (const judgement of judgements) {
    const stored = (anchor.judgements ?? []).find((entry) => entry.code === judgement.code);
    check(`judgements/${judgement.code}.hit`, judgement.hit, stored?.hit);
  }

  console.log('## 11. 与锚内已存块逐数核对');
  if (mismatches.length === 0) {
    console.log('锚内已存块与本次复算一致（productionView / anchorView / strata / candidateFloor / judgements）');
  } else {
    console.error(`[FAIL] 锚内已存块与本次复算不一致 ${mismatches.length} 处：`);
    for (const message of mismatches) console.error(`  - ${message}`);
  }
  console.log('');

  console.log('## 12. 与 --expect 逐数核对（生产视图）');
  const expectMismatches = V2_BANDS.map((band) => ({
    band,
    actual: production.bands.find((entry) => entry.band === band).count,
    expected: expected[band],
  })).filter((entry) => entry.actual !== entry.expected);
  if (expectMismatches.length === 0) {
    console.log(
      `生产视图落带 = ${V2_BANDS.map((band) => expected[band]).join('/')}（与 --expect 一致）`,
    );
  } else {
    console.error('[FAIL] 生产视图落带与 --expect 不一致：');
    for (const entry of expectMismatches) {
      console.error(`  - ${entry.band}: 实际 ${entry.actual} ≠ 期望 ${entry.expected}`);
    }
  }
  console.log('');

  if (mismatches.length > 0 || expectMismatches.length > 0 || corpusMismatches.length > 0) {
    console.error('结果：v2 锚复算未通过（EXIT=1）');
    process.exit(1);
  }
  console.log(
    `结果：复现 v2 生产视图落带 ${V2_BANDS.map(
      (band) => production.bands.find((entry) => entry.band === band).count,
    ).join('/')} ✓（EXIT=0）`,
  );
}

if (process.argv.slice(2).includes('--anchor')) {
  v2Main(process.argv.slice(2));
} else {
  main();
}
