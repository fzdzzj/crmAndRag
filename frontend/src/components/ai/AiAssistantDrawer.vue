<template>
  <Drawer
    v-model:open="visible"
    :width="isMobile ? '100%' : 420"
    placement="right"
    title="AI 助手"
    :closable="true"
    @close="onClose"
  >
    <div class="ai-assistant">
      <!-- 会话列表 -->
      <div class="sessions-bar">
        <Button size="small" @click="newSession">+ 新对话</Button>
        <div v-for="s in sessions" :key="s.id" class="session-item" :class="{ active: currentSessionId === s.id }" @click="switchSession(s.id)">
          {{ s.title || s.id.slice(0,8) }}
          <Button size="small" type="text" @click.stop="deleteSession(s.id)">x</Button>
        </div>
      </div>

      <!-- 消息区 -->
      <div ref="messagesRef" class="messages">
        <div v-for="(msg, idx) in chatMessages" :key="idx" class="msg" :class="msg.role">
          <div class="role">{{ msg.role === 'user' ? '你' : '助手' }}</div>
          <div class="content">
            <pre v-if="msg.msgType === 'actionCard' || msg.msgType === 'draftProgress'">{{ msg.content || JSON.stringify(msg.payload, null, 2) }}</pre>
            <!-- 富文本渲染：AI 消息内容（formatContent 做换行转换 + 行内 [n] 引文锚点），XSS 边界由后端内容治理，故行级豁免 -->
            <!-- eslint-disable-next-line vue/no-v-html -->
            <div v-else @click="onContentClick($event, msg)" v-html="formatContent(msg.content)"></div>
            <div v-if="msg.references && msg.references.length" class="refs">
              <Tag v-for="(reference, i) in msg.references" :key="i" color="blue" @click="jumpReference(reference)">
                {{ refLabel(reference) }}{{ refIdSuffix(reference) }}
              </Tag>
            </div>

            <!-- 知识来源（KB）标签行 -->
            <div v-if="kbSources(msg).length" class="sources">
              <div class="sources-title">来源</div>
              <div class="sources-tags">
                <Tag
                  v-for="(source, i) in kbSources(msg)"
                  :key="i"
                  class="source-tag"
                  color="green"
                  @click="openSource(source)"
                >
                  {{ sourceLabel(source) }}
                  <span v-if="sourcePageNo(source) != null" class="badge">p{{ sourcePageNo(source) }}</span>
                  <span v-if="sourceChunkIndex(source) != null" class="badge">#{{ sourceChunkIndex(source) }}</span>
                  <span v-if="sourceScore(source) != null" class="score">{{ sourceScore(source) }}</span>
                </Tag>
              </div>
            </div>

            <!-- 客户（CUSTOMER）业务来源标签行 -->
            <div v-if="customerSources(msg).length" class="sources">
              <div class="sources-title">客户来源</div>
              <div class="sources-tags">
                <Tag
                  v-for="(source, i) in customerSources(msg)"
                  :key="i"
                  class="source-tag"
                  color="orange"
                  @click="openCustomerSource(source)"
                >
                  {{ customerSourceLabel(source) }}
                </Tag>
              </div>
            </div>

            <!-- 简化 actionCard 渲染（历史或如果下发） -->
            <div v-if="msg.msgType === 'actionCard' && msg.payload" class="action-card">
              <div>操作: {{ actionField(msg, 'actionType') || '待确认' }}</div>
              <div v-if="actionField(msg, 'status') && actionField(msg, 'status') !== 'PENDING'">状态: {{ actionField(msg, 'status') }}</div>
              <div v-else class="actions">
                <Button size="small" :loading="actionLoading" @click="doConfirm(actionPendingId(msg))">确认</Button>
                <Button size="small" @click="doCancel(actionPendingId(msg))">取消</Button>
                <!-- 简化编辑，实际应有候选选择 -->
                <Button size="small" @click="doEdit(actionPendingId(msg), msg.payload)">编辑</Button>
              </div>
            </div>
          </div>
          <div v-if="msg.interrupted" class="interrupted">已中断</div>
        </div>
        <div v-if="isStreaming" class="streaming">...</div>
      </div>

      <!-- 输入 -->
      <div class="input-bar">
        <Input
          v-model:value="inputText"
          placeholder="输入消息，Enter 发送"
          :disabled="isStreaming"
          @press-enter="send"
        />
        <Button :loading="isStreaming" @click="send">发送</Button>
        <Button v-if="isStreaming" @click="chat.stop">停止</Button>
        <Button @click="toggleKb">{{ useKb ? 'KB:开' : 'KB:关' }}</Button>
      </div>

      <ErrorFeedbackButton v-if="errorMsg" class="ai-error-feedback" :code="errorCodeText" :message="errorMsg" />
    </div>
  </Drawer>

  <!-- 知识文档查看抽屉：独立于 AI 会话状态 -->
  <KnowledgeDocumentDrawer
    v-model:open="knowledgeDocOpen"
    :document-id="knowledgeDocId"
    :highlight-chunk-index="knowledgeDocChunkIndex"
    :excerpt="knowledgeDocExcerpt"
  />
</template>

<script setup lang="ts">
import { ref, watch, nextTick, computed } from 'vue';
import { useRouter } from 'vue-router';
import { Drawer, Button, Input, Tag, message as antdMessage } from 'ant-design-vue';
import { useAiChat, type ChatMessage } from '@/hooks/useAiChat';
import { useAiSessions, useCreateAiSession, useDeleteAiSession, useAiSessionMessages } from '@/hooks/useAiSession';
import { useAiAction } from '@/hooks/useAiAction';
import { showErrorToast } from '@/utils/error-toast';
import ErrorFeedbackButton from '@/components/ai/ErrorFeedbackButton.vue';
import KnowledgeDocumentDrawer from '@/components/ai/KnowledgeDocumentDrawer.vue';

const CUSTOMER_SOURCE_PREFIX = 'CUSTOMER_COMPANY:';

const props = defineProps<{ open?: boolean }>();
const emit = defineEmits(['update:open']);

const visible = computed({
  get: () => props.open ?? false,
  set: (v) => emit('update:open', v),
});

const isMobile = ref(window.innerWidth < 768);
const inputText = ref('');
const useKb = ref(true);
const currentSessionId = ref<string>('');
const messagesRef = ref<HTMLElement | null>(null);

const chat = useAiChat();
const chatMessages = computed<ChatMessage[]>(() => chat.messages.value ?? []);
const isStreaming = computed(() => Boolean(chat.isStreaming.value));
const errorMsg = computed(() => chat.error.value ?? '');
const errorCodeText = computed(() => chat.errorCode.value ?? '');
const { sessions, refetch: refetchSessions } = useAiSessions();
const createSession = useCreateAiSession();
const deleteSessionMut = useDeleteAiSession();
const action = useAiAction();

const sessionMessages = useAiSessionMessages(computed(() => currentSessionId.value || null));

/** 知识文档抽屉：仅在有文档被打开时启用，不影响 AI 会话状态 */
const knowledgeDocOpen = ref(false);
const knowledgeDocId = ref<string>('');
const knowledgeDocChunkIndex = ref<number | undefined>(undefined);
const knowledgeDocExcerpt = ref<string | undefined>(undefined);

function openDocument(docId: string, chunkIndex?: number, excerpt?: string) {
  knowledgeDocId.value = docId;
  knowledgeDocChunkIndex.value = chunkIndex;
  knowledgeDocExcerpt.value = excerpt;
  knowledgeDocOpen.value = true;
}

watch(sessionMessages.data, (hist) => {
  if (hist && hist.length) {
    chat.loadHistory(hist);
  }
}, { immediate: true });

watch(visible, (v) => {
  if (v && sessions.value.length === 0) {
    void refetchSessions();
  }
});

const router = useRouter();

/**
 * 渲染 AI 消息正文：换行转 <br/>，并把行内 [n] 引文编号包成可点击锚点。
 * 内容契约信任后端治理，不做转义（与既有 v-html 一致）。
 */
function formatContent(c: string) {
  return (c || '')
    .replace(/\n/g, '<br/>')
    .replace(/\[(\d+)\]/g, '<span class="inline-citation" data-cite="$1">[$1]</span>');
}

/** 消息正文点击：命中 [n] 引文锚点时落到对应引用条目 */
function onContentClick(e: MouseEvent, msg: ChatMessage) {
  const target = (e.target as HTMLElement)?.closest?.('.inline-citation');
  if (!target) return;
  const cite = Number(target.getAttribute('data-cite'));
  if (!Number.isFinite(cite) || cite < 1) return;
  const idx = cite - 1;
  const ref = Array.isArray(msg.references)
    ? msg.references[idx]
    : Array.isArray(msg.sources)
      ? msg.sources[idx]
      : undefined;
  if (ref) {
    jumpCitation(ref);
  }
}

async function send() {
  if (!inputText.value.trim()) return;
  await chat.sendMessage(inputText.value, { useKnowledgeBase: useKb.value, thinking: false });
  inputText.value = '';
  await nextTick();
  scrollToBottom();
}

function scrollToBottom() {
  if (messagesRef.value) {
    messagesRef.value.scrollTop = messagesRef.value.scrollHeight;
  }
}

function toggleKb() {
  useKb.value = !useKb.value;
}

async function newSession() {
  try {
    const s = await createSession.mutateAsync('新 AI 对话');
    currentSessionId.value = String(s?.id ?? '');
    chat.reset();
    await refetchSessions();
  } catch (e: unknown) {
    showErrorToast(e);
  }
}

function switchSession(id: string) {
  currentSessionId.value = id;
  chat.reset();
  // 历史由 watch 加载
}

async function deleteSession(id: string) {
  try {
    await deleteSessionMut.mutateAsync(id);
    if (currentSessionId.value === id) {
      currentSessionId.value = '';
      chat.reset();
    }
  } catch (e: unknown) {
    showErrorToast(e);
  } finally {
    // 无论成败都刷新列表：失败时以服务端状态为准
    await refetchSessions();
  }
}

/** 引用标签展示文本（unknown 安全取值） */
function refLabel(ref: unknown): string {
  const r = (ref ?? {}) as Record<string, unknown>;
  const name = typeof r.name === 'string' ? r.name : typeof r.type === 'string' ? r.type : '';
  return name || '引用';
}

function refIdSuffix(ref: unknown): string {
  const r = (ref ?? {}) as Record<string, unknown>;
  const id = r.id ?? r.documentId;
  return typeof id === 'string' || typeof id === 'number' ? `#${id}` : '';
}

function jumpReference(ref: unknown) {
  // 真实跳转，简化实现；根据 type 路由
  const r = (ref ?? {}) as Record<string, unknown>;
  const type = (r.type || r.sourceType) as string | undefined;
  const id = (r.id || r.documentId) as string | number | undefined;
  if (id) {
    if (type === 'contract' || type === 'CONTRACT') {
      // 实际应 router.push(`/sale/contract-details/${id}`)
      window.open(`/sale/contract-details/${id}`, '_blank');
    } else {
      antdMessage.info(`跳转引用: ${type || ''} ${id}`);
    }
  } else {
    antdMessage.warning('无效引用（无ID）');
  }
}

/** 行内 [n] 引文落地：优先按客户/合同业务引用跳转，其次按知识来源打开文档 */
function jumpCitation(ref: unknown) {
  const r = (ref ?? {}) as Record<string, unknown>;
  const documentId = r.documentId as string | undefined;
  if (typeof documentId === 'string' && documentId.startsWith(CUSTOMER_SOURCE_PREFIX)) {
    openCustomerSource(ref);
    return;
  }
  if (typeof documentId === 'string' && documentId) {
    openDocument(
      documentId,
      r.chunkIndex as number | undefined,
      r.excerpt as string | undefined,
    );
    return;
  }
  jumpReference(ref);
}

/** 判断是否为知识库（KB）来源：有 documentId 且非客户前缀 */
function isKbSource(source: unknown): boolean {
  const s = (source ?? {}) as Record<string, unknown>;
  return typeof s.documentId === 'string'
    && s.documentId.length > 0
    && !s.documentId.startsWith(CUSTOMER_SOURCE_PREFIX);
}

/** 判断是否为客户业务来源：documentId 以 CUSTOMER_COMPANY: 开头 */
function isCustomerSource(source: unknown): boolean {
  const s = (source ?? {}) as Record<string, unknown>;
  return typeof s.documentId === 'string'
    && s.documentId.startsWith(CUSTOMER_SOURCE_PREFIX);
}

/** 取出消息中所有 KB 来源 */
function kbSources(msg: ChatMessage): unknown[] {
  return (Array.isArray(msg.sources) ? msg.sources : []).filter(isKbSource);
}

/** 取出消息中所有客户业务来源 */
function customerSources(msg: ChatMessage): unknown[] {
  return (Array.isArray(msg.sources) ? msg.sources : []).filter(isCustomerSource);
}

function sourceLabel(source: unknown): string {
  const s = (source ?? {}) as Record<string, unknown>;
  const filename = typeof s.filename === 'string' ? s.filename : '';
  return filename || '文档来源';
}

function sourcePageNo(source: unknown): number | null {
  const s = (source ?? {}) as Record<string, unknown>;
  const v = s.pageNo;
  return typeof v === 'number' && Number.isFinite(v) ? v : null;
}

function sourceChunkIndex(source: unknown): number | null {
  const s = (source ?? {}) as Record<string, unknown>;
  const v = s.chunkIndex;
  return typeof v === 'number' && Number.isFinite(v) ? v : null;
}

function sourceScore(source: unknown): string | null {
  const s = (source ?? {}) as Record<string, unknown>;
  const v = s.relevanceScore;
  return typeof v === 'number' && Number.isFinite(v) ? String(Math.round(v * 100) / 100) : null;
}

function customerSourceLabel(source: unknown): string {
  const s = (source ?? {}) as Record<string, unknown>;
  const filename = typeof s.filename === 'string' ? s.filename : '';
  const name = typeof s.name === 'string' ? s.name : '';
  return name || filename || '客户来源';
}

/** 打开 KB 来源：进入文档查看抽屉并滚动到指定 chunk */
function openSource(source: unknown) {
  const s = (source ?? {}) as Record<string, unknown>;
  const documentId = s.documentId as string | undefined;
  if (typeof documentId !== 'string' || !documentId) {
    antdMessage.warning('来源缺少文档标识');
    return;
  }
  openDocument(
    documentId,
    s.chunkIndex as number | undefined,
    s.excerpt as string | undefined,
  );
}

/** 打开客户业务来源：跳转到客户详情页 */
function openCustomerSource(source: unknown) {
  const s = (source ?? {}) as Record<string, unknown>;
  const documentId = s.documentId as string | undefined;
  if (typeof documentId === 'string' && documentId.startsWith(CUSTOMER_SOURCE_PREFIX)) {
    const customerId = documentId.slice(CUSTOMER_SOURCE_PREFIX.length);
    if (customerId) {
      void router.push({ path: '/client/list', query: { 'filters.id': customerId } });
      return;
    }
  }
  antdMessage.warning('无效客户来源');
}

const actionLoading = computed(() => action.loading.value);

/** 从消息 payload（unknown）中安全取出 pendingId */
function actionPendingId(msg: ChatMessage): string | undefined {
  const p = msg.payload as Record<string, unknown> | null;
  return typeof p?.pendingId === 'string' ? p.pendingId : undefined;
}

/** 从消息 payload（unknown）中安全取出字符串字段用于展示 */
function actionField(msg: ChatMessage, field: string): string {
  const p = msg.payload as Record<string, unknown> | null;
  const v = p?.[field];
  return typeof v === 'string' || typeof v === 'number' ? String(v) : '';
}

async function doConfirm(pendingId?: string) {
  if (!pendingId) return;
  try {
    const res = await action.confirm(pendingId);
    // 使用返回状态刷新卡片，而非硬编码
    const rawStatus = (res as Record<string, unknown> | undefined)?.status;
    const status = typeof rawStatus === 'string' ? rawStatus : 'CONFIRMED';
    antdMessage.success(`已确认，状态: ${status}`);
    // 刷新当前消息或会话
    void sessionMessages.refetch?.();
  } catch {
    /* confirm 内部已提示 */
  }
}

async function doCancel(pendingId?: string) {
  if (!pendingId) return;
  await action.cancelAction(pendingId);
  void sessionMessages.refetch?.();
}

async function doEdit(pendingId?: string, currentPayload?: unknown) {
  if (!pendingId) return;
  // 简化：合并示例，实际应有候选选择 UI
  const merged = { ...(currentPayload as Record<string, unknown> | undefined), note: 'edited-by-frontend' };
  try {
    await action.edit(pendingId, merged);
    void sessionMessages.refetch?.();
  } catch {
    /* edit 内部已提示 */
  }
}

function onClose() {
  void chat.stop();
  // 不清空历史
}

defineExpose({ open: () => { visible.value = true; } });
</script>

<style scoped>
.ai-assistant { display: flex; flex-direction: column; height: 100%; }
.sessions-bar { display: flex; gap: 4px; flex-wrap: wrap; padding: 8px; border-bottom: 1px solid #f0f0f0; }
.session-item { padding: 2px 6px; border: 1px solid #eee; border-radius: 4px; cursor: pointer; font-size: 12px; }
.session-item.active { background: #e6f7ff; }
.messages { flex: 1; overflow: auto; padding: 8px; font-size: 14px; }
.msg { margin-bottom: 12px; }
.msg.user .content { background: #f0f0f0; padding: 6px; border-radius: 6px; }
.msg.assistant .content { background: #fafafa; padding: 6px; border-radius: 6px; }
.role { font-size: 11px; color: #888; margin-bottom: 2px; }
.refs { margin-top: 4px; }
.refs .ant-tag { cursor: pointer; }
.sources { margin-top: 6px; }
.sources-title { font-size: 11px; color: #888; margin-bottom: 3px; }
.sources-tags { display: flex; flex-wrap: wrap; gap: 4px; }
.source-tag { cursor: pointer; }
.source-tag .badge {
  display: inline-block;
  margin-left: 4px;
  padding: 0 4px;
  font-size: 10px;
  line-height: 14px;
  border-radius: 4px;
  background: rgba(0,0,0,0.08);
  color: #555;
}
.source-tag .score {
  display: inline-block;
  margin-left: 4px;
  font-size: 10px;
  color: rgba(0,0,0,0.45);
}
.inline-citation {
  display: inline-block;
  padding: 0 2px;
  margin: 0 1px;
  color: #1677ff;
  cursor: pointer;
  border-radius: 3px;
  user-select: none;
}
.inline-citation:hover { background: #e6f4ff; }
.action-card { border: 1px dashed #faad14; padding: 4px; margin-top: 4px; font-size: 12px; }
.input-bar { display: flex; gap: 8px; padding: 8px; border-top: 1px solid #f0f0f0; }
.streaming { color: #888; }
</style>
