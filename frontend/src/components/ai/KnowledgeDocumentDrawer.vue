<template>
  <Drawer
    :open="open"
    :title="filename || '知识来源文档'"
    :width="isMobile ? '100%' : 580"
    placement="right"
    :closable="true"
    @close="onClose"
  >
    <Spin :spinning="loading">
      <div v-if="error" class="error-tip">{{ error }}</div>
      <div v-else-if="!chunks.length" class="empty-tip">暂无内容</div>
      <div v-else class="chunk-list">
        <div
          v-for="chunk in chunks"
          :key="chunk.chunkId ?? chunk.chunkIndex"
          :ref="(el) => setChunkRef(chunk.chunkIndex, el)"
          class="chunk-block"
          :class="{ highlighted: chunk.chunkIndex === effectiveHighlightIndex }"
        >
          <div v-if="chunk.pageNo != null" class="chunk-meta">
            <span class="page-badge">第 {{ chunk.pageNo }} 页</span>
            <span v-if="chunk.chunkIndex != null" class="chunk-index">#{{ chunk.chunkIndex }}</span>
          </div>
          <div v-else-if="chunk.chunkIndex != null" class="chunk-meta">
            <span class="chunk-index">#{{ chunk.chunkIndex }}</span>
          </div>
          <!-- 高亮块：尽量匹配 excerpt 子串，否则整段高亮；内容为已转义文本 -->
          <!-- eslint-disable-next-line vue/no-v-html -->
          <div class="chunk-text" :class="{ 'is-excerpt': chunk.chunkIndex === effectiveHighlightIndex && highlightedText(chunk) !== '' }" v-html="renderChunk(chunk)"></div>
        </div>
      </div>
    </Spin>
  </Drawer>
</template>

<script setup lang="ts">
import {
  computed,
  nextTick,
  ref,
  watch,
  type ComponentPublicInstance,
} from 'vue';
import { Drawer, Spin } from 'ant-design-vue';
import { axiosInstance } from '@/api/apiClient';

interface ChunkRecord {
  chunkId?: string | number;
  documentId?: string;
  chunkText?: string;
  filename?: string;
  chunkIndex?: number;
  pageNo?: number;
  rowIndex?: number;
}

interface ChunkPage {
  records?: ChunkRecord[];
  total?: number;
}

const props = defineProps<{
  open?: boolean;
  documentId?: string;
  highlightChunkIndex?: number | null;
  excerpt?: string | null;
}>();
const emit = defineEmits<{ (e: 'update:open', v: boolean): void }>();

const PAGE_SIZE = 100;
const loading = ref(false);
const chunks = ref<ChunkRecord[]>([]);
const filename = ref('');
const error = ref('');
const isMobile = ref(typeof window !== 'undefined' && window.innerWidth < 768);
const chunkRefs = new Map<number | undefined, HTMLElement>();

interface HighlightedChunk {
  chunkIndex: number | undefined;
  before: string;
  match: string;
  after: string;
}
const highlightCache = new Map<number | undefined, HighlightedChunk>();

const effectiveHighlightIndex = computed(() => {
  if (typeof props.highlightChunkIndex === 'number') return props.highlightChunkIndex;
  return undefined;
});

function setChunkRef(
  chunkIndex: number | undefined,
  el: Element | ComponentPublicInstance | null,
) {
  const dom = el as HTMLElement | null;
  if (dom) {
    chunkRefs.set(chunkIndex, dom);
  }
}

/** 归一化空白，便于 excerpt 匹配不因换行/空格差异失败 */
function normalizeWhitespace(s: string): string {
  return s.replace(/\s+/g, ' ').trim();
}

/** 安全转义 HTML 文本（匹配/非匹配段分别转义） */
function escapeHtml(s: string): string {
  return s
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;');
}

/**
 * 在 chunk 文本中查找 excerpt 子串并切分为 before/match/after。
 * 不依赖易碎的全局正则：用 indexOf 定位一次，找不到则整段视为 before。
 */
function splitHighlight(text: string, excerpt: string): HighlightedChunk {
  const normalizedText = normalizeWhitespace(text);
  const normalizedExcerpt = normalizeWhitespace(excerpt);
  // excerpt 过短（如 <2 字符）易误匹配，退回整段展示
  if (normalizedExcerpt.length < 2) {
    return { chunkIndex: undefined, before: text, match: '', after: '' };
  }
  const index = normalizedText.indexOf(normalizedExcerpt);
  if (index < 0) {
    return { chunkIndex: undefined, before: text, match: '', after: '' };
  }
  const start = index;
  const end = index + normalizedExcerpt.length;
  // 尽量做更长的、可连续匹配的片段——直接取命中段
  return {
    chunkIndex: undefined,
    before: normalizedText.slice(0, start),
    match: normalizedText.slice(start, end),
    after: normalizedText.slice(end),
  };
}

function highlightedText(chunk: ChunkRecord): string {
  if (chunk.chunkIndex !== effectiveHighlightIndex.value) return '';
  if (!props.excerpt || !chunk.chunkText) return '';
  const cache = highlightCache.get(chunk.chunkIndex);
  return cache?.match ?? '';
}

/** 渲染单个 chunk：高亮块内对 excerpt 命中子串加 <mark> */
function renderChunk(chunk: ChunkRecord): string {
  const text = chunk.chunkText ?? '';
  if (chunk.chunkIndex === effectiveHighlightIndex.value && props.excerpt) {
    let hit = highlightCache.get(chunk.chunkIndex);
    if (!hit) {
      hit = splitHighlight(text, props.excerpt);
      highlightCache.set(chunk.chunkIndex, hit);
    }
    if (hit.match) {
      return `${escapeHtml(hit.before)}<mark>${escapeHtml(hit.match)}</mark>${escapeHtml(hit.after)}`;
    }
  }
  return escapeHtml(text);
}

async function loadDocument() {
  const documentId = props.documentId;
  if (!documentId) return;
  loading.value = true;
  error.value = '';
  highlightCache.clear();
  chunks.value = [];
  filename.value = '';
  try {
    const collected: ChunkRecord[] = [];
    let pageNum = 1;
    let total = Infinity;
    const seenIndex = new Set<number | undefined>();
    // 逐页拉取，最多 100 条/页；直到本页不足一页或取满全量为止
    while (collected.length < 10000) {
      const res = await axiosInstance.get(
        `/knowledge/documents/${encodeURIComponent(documentId)}/chunks`,
        { params: { pageNum, pageSize: PAGE_SIZE } },
      );
      const pageData = (res.data?.data ?? {}) as ChunkPage;
      const records = Array.isArray(pageData.records) ? pageData.records : [];
      if (typeof pageData.total === 'number') {
        total = pageData.total;
      }
      for (const r of records) {
        // 去重：同 chunkIndex 保留首个；缺索引的也保留
        if (r.chunkIndex == null || !seenIndex.has(r.chunkIndex)) {
          if (r.chunkIndex != null) seenIndex.add(r.chunkIndex);
          collected.push(r);
          if (!filename.value && r.filename) filename.value = r.filename;
        }
      }
      if (records.length === 0 || records.length < PAGE_SIZE) break;
      if (collected.length >= total) break;
      pageNum += 1;
    }
    chunks.value = collected.sort((a, b) =>
      (a.chunkIndex ?? 0) - (b.chunkIndex ?? 0),
    );
  } catch (e) {
    error.value = (e as Error)?.message || '加载文档失败';
    chunks.value = [];
  } finally {
    loading.value = false;
  }
}

async function scrollToHighlight() {
  const idx = effectiveHighlightIndex.value;
  if (idx === undefined) return;
  await nextTick();
  const target = chunkRefs.get(idx);
  if (target) {
    target.scrollIntoView({ block: 'start', behavior: 'smooth' });
  }
}

watch(
  [() => props.open, () => props.documentId],
  ([openVal, docId]) => {
    if (openVal && docId) {
      void loadDocument().then(() => scrollToHighlight());
    }
  },
  { immediate: true },
);

function onClose() {
  emit('update:open', false);
}
</script>

<style scoped>
.error-tip {
  color: #cf1322;
  padding: 16px;
  font-size: 13px;
}
.empty-tip {
  color: #999;
  padding: 24px;
  text-align: center;
  font-size: 13px;
}
.chunk-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.chunk-block {
  border: 1px solid #f0f0f0;
  border-radius: 6px;
  padding: 10px 12px;
  background: #fff;
}
.chunk-block.highlighted {
  border-color: #91caff;
  background: #f0f9ff;
  box-shadow: 0 0 0 2px rgba(22, 119, 255, 0.12);
}
.chunk-meta {
  margin-bottom: 6px;
  display: flex;
  gap: 8px;
  align-items: center;
}
.page-badge {
  display: inline-block;
  padding: 0 6px;
  font-size: 11px;
  line-height: 18px;
  border-radius: 4px;
  background: #e6f4ff;
  color: #1677ff;
}
.chunk-index {
  font-size: 11px;
  color: #888;
}
.chunk-text {
  font-size: 13px;
  line-height: 1.7;
  color: #333;
  white-space: pre-wrap;
  word-break: break-word;
}
.chunk-text :deep(mark) {
  background: #ffe58f;
  color: #ad6800;
  padding: 0 1px;
  border-radius: 2px;
}
</style>