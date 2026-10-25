import { createHash } from 'node:crypto';
import { existsSync, readFileSync, readdirSync, writeFileSync } from 'node:fs';
import { dirname, join, relative, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';
import UI from '@/constants/uiEndpointPermissions';

/**
 * 卡 30（ui-permission-coverage-stocktake）覆盖率门禁。
 *
 * <p>小表 {@code uiEndpointPermissions.ts} 的编译期护栏只守「登记得对不对」（键/值契约校验），
 * 守不住「该登的登了没有」（表是 Partial，漏登合法）；运行期 fail-closed 的后果是模板按钮静默消失。
 * 本门禁把使用面钉在模板上，断言 used ⊆ registered ⊆ contract 三层：
 *
 * <ul>
 *   <li>used：src 下全部 .vue 模板里 `<Permission endpoint="…">` / `:endpoint="'…'"` 的字面量集合
 *     （含三元绑定的全部分支字面量；`src/api/axios` 生成物不扫）；</li>
 *   <li>registered：运行期小表全量键（import 实测，非源码 grep）；</li>
 *   <li>contract：`types/endpointPermission.gen.ts` 全量键（type-only 生成物，只能文本解析）。</li>
 * </ul>
 *
 * <p>锚：`docs/ui-permission-coverage-v1.json` 默认逐字节复算；写模式
 * `UI_PERMISSION_COVERAGE_UPDATE=1 pnpm vitest run`（bash 形式，等价 proposal 的 `-D` 开关）。
 */

const HERE = dirname(fileURLToPath(import.meta.url));
const FRONTEND_ROOT = resolve(HERE, '..', '..', '..');
const SRC_ROOT = join(FRONTEND_ROOT, 'src');
const GEN_FILE = join(SRC_ROOT, 'types', 'endpointPermission.gen.ts');
const ANCHOR_JSON = resolve(FRONTEND_ROOT, '..', 'docs', 'ui-permission-coverage-v1.json');
const ANCHOR_MD = resolve(FRONTEND_ROOT, '..', 'docs', 'ui-permission-coverage-v1.md');
const AXIOS_GEN_DIR = join(SRC_ROOT, 'api', 'axios');
const UPDATE_ENV = 'UI_PERMISSION_COVERAGE_UPDATE';

/** 动态绑定白名单（R2/D1）：解析不出任何端点字面量的 `:endpoint` 绑定必须在此登记，否则门禁红；条目过期（现场已消失）同样红。 */
const DYNAMIC_BINDING_WHITELIST: ReadonlyArray<{ file: string; line: number; reason: string }> = [];

/** 僵尸登记候选（D3：本卡只标注不删，删除属另卡）。每条必须仍属 registered∖used，键复活（模板又用上）即红。 */
const ZOMBIE_CANDIDATES: ReadonlyArray<{ key: string; note: string }> = [
  {
    key: 'DELETE /contract',
    note: '模板使用面 0 站点：contract.page.vue 只有建/查/改/预警触发入口，无删除按钮；登记保留，删除另卡定夺。',
  },
];

/** 本卡实测登记数下限（57 条）。D3 裁决本 lane 小表只增不删——缩表必须先动这行并另卡留痕。 */
const REGISTERED_FLOOR = 57;

const PERMISSION_TAG_RE = /<Permission\b[^>]*>/g;
const STATIC_ENDPOINT_RE = /(?:^|\s)endpoint="([^"]+)"/;
const DYNAMIC_ENDPOINT_RE = /(?:^|\s):endpoint="([^"]+)"/;
const ALLOW_RE = /(?:^|\s):allow=/;
const ENDPOINT_LITERAL_RE = /'([A-Z]+ \/[^']+)'/g;

interface ScanFace {
  usedKeys: Map<string, string[]>;
  usedSites: number;
  allowOnlySites: string[];
  dynamicResolved: Array<{ file: string; line: number; expression: string; keys: string[] }>;
  dynamicUnresolved: Array<{ file: string; line: number; expression: string }>;
  bareSites: string[];
  vueFilesScanned: number;
}

function toPosix(p: string): string {
  return p.split('\\').join('/');
}

function listFilesRecursively(dir: string, extensions: readonly string[]): string[] {
  const out: string[] = [];
  const entries = readdirSync(dir, { withFileTypes: true }).sort((a, b) =>
    a.name < b.name ? -1 : a.name > b.name ? 1 : 0,
  );
  for (const entry of entries) {
    const child = join(dir, entry.name);
    if (entry.isDirectory()) {
      if (resolve(child) === resolve(AXIOS_GEN_DIR)) continue; // 生成物 + gitignore（任务卡坑 1）
      out.push(...listFilesRecursively(child, extensions));
    } else if (
      entry.isFile() &&
      extensions.some((ext) => entry.name.toLowerCase().endsWith(ext))
    ) {
      out.push(child);
    }
  }
  return out;
}

function lineOf(content: string, index: number): number {
  return content.slice(0, index).split('\n').length;
}

function scanTemplates(): ScanFace {
  const face: ScanFace = {
    usedKeys: new Map<string, string[]>(),
    usedSites: 0,
    allowOnlySites: [],
    dynamicResolved: [],
    dynamicUnresolved: [],
    bareSites: [],
    vueFilesScanned: 0,
  };
  for (const filePath of listFilesRecursively(SRC_ROOT, ['.vue'])) {
    face.vueFilesScanned += 1;
    const fileRel = toPosix(relative(FRONTEND_ROOT, filePath));
    const content = readFileSync(filePath, 'utf8');
    for (const match of content.matchAll(PERMISSION_TAG_RE)) {
      const tag = match[0];
      const line = lineOf(content, match.index ?? 0);
      const site = `${fileRel}:${line}`;
      const dynamic = DYNAMIC_ENDPOINT_RE.exec(tag);
      const literalKeys = [...tag.matchAll(ENDPOINT_LITERAL_RE)].map((m) => m[1]);
      if (STATIC_ENDPOINT_RE.test(tag) && !dynamic) {
        face.usedSites += 1;
        for (const key of literalKeys) pushSite(face.usedKeys, key, site);
      } else if (dynamic) {
        const expression = dynamic[1];
        const branchKeys = [...expression.matchAll(ENDPOINT_LITERAL_RE)].map((m) => m[1]);
        if (branchKeys.length > 0) {
          face.usedSites += 1;
          for (const key of branchKeys) pushSite(face.usedKeys, key, site);
          face.dynamicResolved.push({ file: fileRel, line, expression, keys: branchKeys });
        } else {
          face.dynamicUnresolved.push({ file: fileRel, line, expression });
        }
      } else if (ALLOW_RE.test(tag)) {
        face.allowOnlySites.push(site);
      } else {
        // 解析器完整性守卫：既无 endpoint 也无 allow 的 <Permission>，要么是模板死代码，要么是本扫描器漏读属性（如属性值含 >），都不许静默。
        face.bareSites.push(site);
      }
    }
  }
  return face;
}

function pushSite(map: Map<string, string[]>, key: string, site: string): void {
  const sites = map.get(key);
  if (sites) sites.push(site);
  else map.set(key, [site]);
}

interface ContractFace {
  keys: string[];
  securedNames: Map<string, string>;
  openCount: number;
}

function parseContract(): ContractFace {
  const text = readFileSync(GEN_FILE, 'utf8');
  const secured = [...text.matchAll(/'([^']+)': \{ code: \d+; name: '([^']+)' \};/g)];
  const open = [...text.matchAll(/'([^']+)': \{ status: 'OPEN' \};/g)];
  const entryLineCount = [...text.matchAll(/^\s*'[^']+': \{/gm)].length;
  if (entryLineCount !== secured.length + open.length) {
    throw new Error(
      `endpointPermission.gen.ts 解析面与条目行数不一致（条目行 ${entryLineCount}，解析出 SECURED ${secured.length} + OPEN ${open.length}），生成物格式疑似漂移，请校准本测试的正则`,
    );
  }
  const securedNames = new Map<string, string>();
  for (const m of secured) securedNames.set(m[1], m[2]);
  const keys = [...securedNames.keys(), ...open.map((m) => m[1])];
  if (new Set(keys).size !== keys.length) {
    throw new Error('endpointPermission.gen.ts 解析出重复键，生成物疑似漂移');
  }
  return { keys, securedNames, openCount: open.length };
}

function findPermissionOfEndpointCallSites(): string[] {
  const allowed = new Set(['src/utils/endpointPermission.ts', 'src/components/common/Permission.vue']);
  const hits: string[] = [];
  for (const filePath of listFilesRecursively(SRC_ROOT, ['.ts', '.vue'])) {
    const rel = toPosix(relative(FRONTEND_ROOT, filePath));
    if (allowed.has(rel)) continue;
    if (rel.includes('/__tests__/') || /\.test\.ts$/.test(rel)) continue;
    if (/\bpermissionOfEndpoint\b/.test(readFileSync(filePath, 'utf8'))) hits.push(rel);
  }
  return hits;
}

function buildAnchorJson(): string {
  const face = scanTemplates();
  const registered = Object.entries(UI as Record<string, string | null>);
  const contract = parseContract();
  const usedSet = face.usedKeys;
  const registeredSet = new Set(registered.map(([key]) => key));
  const contractSet = new Set(contract.keys);
  const usedMinusRegistered = [...usedSet.keys()].filter((k) => !registeredSet.has(k)).sort();
  const registeredMinusContract = [...registeredSet].filter((k) => !contractSet.has(k)).sort();
  const registeredMinusUsed = [...registeredSet].filter((k) => !usedSet.has(k)).sort();
  const registeredMap = new Map(registered);

  const usedAnchor: Record<string, string[]> = {};
  for (const key of [...usedSet.keys()].sort()) usedAnchor[key] = (usedSet.get(key) as string[]).slice().sort();
  const registeredAnchor: Record<string, string | null> = {};
  for (const [key, value] of registered) registeredAnchor[key] = value;

  const anchor = {
    schema: 'ui-permission-coverage-v1',
    verdict: usedMinusRegistered.length === 0 ? 'COVERAGE-CLOSED' : 'GAPS-FIXED',
    counts: {
      used: Object.keys(usedAnchor).length,
      usedSites: face.usedSites,
      allowOnlySites: face.allowOnlySites.length,
      registered: registered.length,
      contract: contract.keys.length,
      contractSecured: contract.keys.length - contract.openCount,
      contractOpen: contract.openCount,
      vueFilesScanned: face.vueFilesScanned,
    },
    diffs: { usedMinusRegistered, registeredMinusContract, registeredMinusUsed },
    dynamicBindingWhitelist: DYNAMIC_BINDING_WHITELIST,
    dynamicBindingsResolved: face.dynamicResolved,
    zombieCandidates: ZOMBIE_CANDIDATES.map((c) => ({
      key: c.key,
      value: registeredMap.get(c.key) ?? null,
      note: c.note,
    })),
    used: usedAnchor,
    registered: registeredAnchor,
    contractDigest: {
      sha256: createHash('sha256').update([...contract.keys].sort().join('\n')).digest('hex'),
      count: contract.keys.length,
      secured: contract.keys.length - contract.openCount,
      open: contract.openCount,
    },
  };
  return `${JSON.stringify(anchor, null, 2)}\n`;
}

describe('endpointPermissionCoverage（卡 30：模板使用面 ⊆ 运行期小表 ⊆ 契约）', () => {
  it('used ⊆ registered：模板 <Permission endpoint> 的每个端点都已登记小表（漏登 = 按钮静默消失）', () => {
    const face = scanTemplates();
    const registeredSet = new Set(Object.keys(UI as Record<string, string | null>));
    const missing = [...face.usedKeys.keys()].filter((k) => !registeredSet.has(k)).sort();
    const detail = missing
      .map((k) => `  - ${k}（模板站点：${(face.usedKeys.get(k) as string[]).join(', ')}）`)
      .join('\n');
    expect(
      missing,
      `存在 ${missing.length} 个 fail-closed 缺口（模板已用、小表未登记），补登记值只许取契约 name / OPEN 的 null：\n${detail}`,
    ).toEqual([]);
    // 防空扫描恒绿：使用面必须有实测内容。
    expect(face.usedKeys.size, '使用面扫描结果非空（防空扫描造成恒绿）').toBeGreaterThan(0);
    expect(face.usedSites).toBeGreaterThan(0);
  });

  it('registered ⊆ contract：小表键全部存在于生成物契约（运行期证明，不靠编译期推理）', () => {
    const contract = parseContract();
    const contractSet = new Set(contract.keys);
    const registeredKeys = Object.keys(UI as Record<string, string | null>);
    const ghosts = registeredKeys.filter((k) => !contractSet.has(k)).sort();
    expect(
      ghosts,
      `registered∖contract 应恒空（编译期护栏已保证），运行期实测越界 ${ghosts.length} 条：${ghosts.join(', ')}`,
    ).toEqual([]);
    // D3：本 lane 小表只增不删，缩表先另卡。
    expect(registeredKeys.length, '登记数下限（D3 只增不删）').toBeGreaterThanOrEqual(REGISTERED_FLOOR);
  });

  it('动态绑定全量处置：可解析绑定全分支计入 used，不可解析必须在白名单（禁止静默跳过）', () => {
    const face = scanTemplates();
    const whitelistKeys = new Set(DYNAMIC_BINDING_WHITELIST.map((w) => `${w.file}:${w.line}`));
    const unresolvedKeys = new Set(face.dynamicUnresolved.map((u) => `${u.file}:${u.line}`));
    const notWhitelisted = [...unresolvedKeys].filter((k) => !whitelistKeys.has(k));
    expect(
      notWhitelisted,
      `存在未白名单的不可解析 :endpoint 动态绑定（每项需 文件:行号 + 理由）：\n${notWhitelisted.join('\n')}`,
    ).toEqual([]);
    const stale = [...whitelistKeys].filter((k) => !unresolvedKeys.has(k));
    expect(stale, `白名单条目已过期（现场不存在不可解析绑定）：\n${stale.join('\n')}`).toEqual([]);
    // 解析器完整性：无属性 <Permission> 一律红，杜绝静默漏读。
    expect(face.bareSites, `既无 endpoint 也无 allow 的 <Permission>（模板死代码或扫描器漏读）：\n${face.bareSites.join('\n')}`).toEqual([]);
  });

  it('僵尸候选不过期：ZOMBIE_CANDIDATES 每条仍属 registered∖used（键复活即红）', () => {
    const face = scanTemplates();
    const registeredSet = new Set(Object.keys(UI as Record<string, string | null>));
    const zombies = new Set([...registeredSet].filter((k) => !face.usedKeys.has(k)));
    const revived = ZOMBIE_CANDIDATES.filter((c) => !zombies.has(c.key)).map((c) => c.key);
    expect(revived, `僵尸候选已复活（模板重新用上或登记被删），请从候选表移除：${revived.join(', ')}`).toEqual([]);
    expect(zombies.size, '僵尸集合非空（registered∖used）').toBeGreaterThan(0);
  });

  it('锚 docs/ui-permission-coverage-v1.json 逐字节复算一致（写模式：UI_PERMISSION_COVERAGE_UPDATE=1 pnpm vitest run）', () => {
    const json = buildAnchorJson();
    // 生成器自检（任务卡坑 6）：纯 LF，且末尾不得有空行（卡 29 事故复现点）。
    expect(json.includes('\r'), '锚 JSON 必须纯 LF').toBe(false);
    expect(json.endsWith('\n') && !json.endsWith('\n\n'), '锚 JSON 末尾不得有空行').toBe(true);
    if (process.env[UPDATE_ENV] === '1') {
      writeFileSync(ANCHOR_JSON, json, 'utf8');
    }
    expect(existsSync(ANCHOR_JSON), '锚 JSON 不存在，先跑写模式生成：UI_PERMISSION_COVERAGE_UPDATE=1 pnpm vitest run').toBe(true);
    const committed = readFileSync(ANCHOR_JSON, 'utf8');
    expect(committed === json, '锚与复算结果逐字节不一致（三集合/行号漂移），确认后用写模式重锚').toBe(true);
  });

  it('锚 .md 存在且 ≤40 行；permissionOfEndpoint 契约外调用点为 0（使用面=模板 的前提守卫）', () => {
    expect(existsSync(ANCHOR_MD), '锚 .md 不存在（docs/ui-permission-coverage-v1.md）').toBe(true);
    const md = readFileSync(ANCHOR_MD, 'utf8');
    const lineCount = md.replace(/\r?\n$/, '').split(/\r?\n/).length;
    expect(lineCount, `锚 .md 限 ≤40 行，实测 ${lineCount}`).toBeLessThanOrEqual(40);
    const rogueCallSites = findPermissionOfEndpointCallSites();
    expect(
      rogueCallSites,
      `permissionOfEndpoint 出现契约外调用点（使用面不再等于模板面，本门禁会漏）：${rogueCallSites.join(', ')}`,
    ).toEqual([]);
  });
});
