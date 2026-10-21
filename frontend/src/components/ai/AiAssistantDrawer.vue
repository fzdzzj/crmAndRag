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
                  <img
                    v-if="isImageSource(source) && imageThumbUrl(source)"
                    class="source-thumb"
                    :src="imageThumbUrl(source)"
                    alt="图片切片"
                  />
                  {{ sourceLabel(source) }}
                  <span v-if="sourcePageNo(source) != null" class="badge">p{{ sourcePageNo(source) }}</span>
                  <span v-if="sourceChunkIndex(source) != null" class="badge">#{{ sourceChunkIndex(source) }}</span>
                  <span v-if="sourceScore(source) != null" class="score">{{ sourceScore(source) }}</span>
                </Tag>
              </div>
            </div>

            <!-- 业务实体来源标签行（客户/活动/商机/合同/任务） -->
            <div v-if="businessSources(msg).length" class="sources">
              <div class="sources-title">业务来源</div>
              <div class="sources-tags">
                <Tag
                  v-for="(source, i) in businessSources(msg)"
                  :key="i"
                  class="source-tag"
                  color="orange"
                  @click="openBusinessSource(source)"
                >
                  {{ businessSourceLabel(source) }}
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

      <!-- 客户健康度诊断面板：「📊 诊断客户健康度」指令触发，或客户上下文明确时展示 -->
      <div
        v-if="insightCompanyId != null"
        class="flex flex-col gap-2 border-t border-neutral-200 p-2"
      >
        <div class="flex items-center justify-between">
          <span class="text-xs font-medium text-neutral-500">
            📊 客户健康度诊断（客户 #{{ insightCompanyId }}）
          </span>
          <Button size="small" type="text" @click="insightCompanyId = null">收起</Button>
        </div>
        <CustomerInsightPanel
          :key="insightCompanyId"
          :company-id="insightCompanyId"
          @created="onInsightActionCreated"
        />
      </div>

      <!-- 快捷指令药丸（Prompt Starter Pills）：高频业务场景一键填充并发送 -->
      <div v-if="!isStreaming" class="pills-bar">
        <button
          v-for="pill in starterPills"
          :key="pill"
          type="button"
          class="starter-pill"
          @click="sendPill(pill)"
        >
          {{ pill }}
        </button>
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
import { ref, watch, nextTick, computed, onUnmounted } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { Drawer, Button, Input, Tag, message as antdMessage } from 'ant-design-vue';
import { useAiChat, type ChatMessage } from '@/hooks/useAiChat';
import { useAiSessions, useCreateAiSession, useDeleteAiSession, useAiSessionMessages } from '@/hooks/useAiSession';
import { useAiAction } from '@/hooks/useAiAction';
import { showErrorToast } from '@/utils/error-toast';
import { axiosInstance } from '@/api/apiClient';
import ErrorFeedbackButton from '@/components/ai/ErrorFeedbackButton.vue';
import KnowledgeDocumentDrawer from '@/components/ai/KnowledgeDocumentDrawer.vue';
import CustomerInsightPanel from '@/components/ai/CustomerInsightPanel.vue';

const BUSINESS_SOURCE_TYPES = ['CUSTOMER', 'BUSINESS_ACTIVITY', 'SALES_OPPORTUNITY', 'CONTRACT', 'CONTACT_TASK'];

/** 图片切片来源类型（add-kb-image-slice-retrieval 任务 3.1 受控解冻取值，大写，与业务实体来源同形） */
const IMAGE_SOURCE_TYPE = 'IMAGE';

/** 快捷指令药丸（add-ai-customer-360-insight 任务 4.1）：高频业务场景一键填充并发送 */
const INSIGHT_PILL = '📊 诊断客户健康度';
const starterPills = [
  INSIGHT_PILL,
  '📋 汇总今日待办任务',
  '📈 统计本月商机进展',
  '📝 起草商业拜访纪要',
];

/** 业务来源 documentId 可能带的历史前缀（旧客户口径 CUSTOMER_COMPANY:，新口径为纯主键字符串） */
const BUSINESS_DOC_ID_PREFIXES = ['CUSTOMER_COMPANY:', 'BUSINESS_ACTIVITY:', 'SALES_OPPORTUNITY:', 'CONTRACT:', 'CONTACT_TASK:'];

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

const route = useRoute();
const router = useRouter();

/** 路由内客户上下文：客户列表页以 query `filters.id` 携带当前客户主键 */
const routeCompanyId = computed<number | null>(() => {
  const raw = route.query['filters.id'];
  const id = Number(Array.isArray(raw) ? raw[0] : raw);
  return Number.isFinite(id) && id > 0 ? id : null;
});

/** 上下文客户：优先路由 query，其次对话内最近的 CUSTOMER 业务来源 */
const contextCompanyId = computed<number | null>(() => {
  if (routeCompanyId.value != null) return routeCompanyId.value;
  for (let i = chatMessages.value.length - 1; i >= 0; i -= 1) {
    for (const source of businessSources(chatMessages.value[i])) {
      const s = (source ?? {}) as Record<string, unknown>;
      if (s.sourceType === 'CUSTOMER' || s.type === 'CUSTOMER') {
        const id = Number(businessSourceId(source));
        if (Number.isFinite(id) && id > 0) return id;
      }
    }
  }
  return null;
});

/** 诊断面板宿主：快捷指令触发或客户上下文明确时展示，可收起 */
const insightCompanyId = ref<number | null>(null);

watch(visible, (v) => {
  if (v && routeCompanyId.value != null) {
    insightCompanyId.value = routeCompanyId.value;
  }
}, { immediate: true });

/** 转待办/转活动成功（draft 已生成）：可见反馈 + 刷新会话（操作卡片随之下发） */
function onInsightActionCreated(pendingId: string) {
  antdMessage.success(`已生成待确认草稿（${pendingId}），请在操作卡片中确认`);
  void sessionMessages.refetch?.();
}

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

/** 药丸一键填充并发送；「📊 诊断客户健康度」额外联动诊断面板（上下文客户明确时） */
function sendPill(text: string) {
  if (isStreaming.value) return;
  if (text === INSIGHT_PILL) {
    if (contextCompanyId.value != null) {
      insightCompanyId.value = contextCompanyId.value;
    } else {
      antdMessage.info('未识别到客户上下文，可从客户列表进入或直接在对话中指明客户');
    }
  }
  inputText.value = text;
  void send();
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
  // 真实跳转，简化实现；根据 type/sourceType 路由
  const r = (ref ?? {}) as Record<string, unknown>;
  const type = (r.type || r.sourceType) as string | undefined;
  const id = (r.id || r.documentId) as string | number | undefined;
  if (!type || !id) {
    antdMessage.warning('无效引用（无类型或ID）');
    return;
  }
  if (BUSINESS_SOURCE_TYPES.includes(type)) {
    jumpToBusiness(type, String(id));
    return;
  }
  antdMessage.info(`跳转引用: ${type} ${id}`);
}

/** 行内 [n] 引文落地：优先按业务实体引用深度跳转，其次按知识来源打开文档 */
function jumpCitation(ref: unknown) {
  const r = (ref ?? {}) as Record<string, unknown>;
  const type = (r.sourceType ?? r.type) as string | undefined;
  if (typeof type === 'string' && BUSINESS_SOURCE_TYPES.includes(type)) {
    jumpToBusiness(type, businessSourceId(r));
    return;
  }
  const documentId = r.documentId as string | undefined;
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

/** 判断是否为知识库（KB）来源：有 documentId 且 sourceType 非业务实体来源（不再按单一客户前缀误判） */
function isKbSource(source: unknown): boolean {
  const s = (source ?? {}) as Record<string, unknown>;
  const type = (s.sourceType ?? s.type) as string | undefined;
  if (typeof type === 'string' && BUSINESS_SOURCE_TYPES.includes(type)) {
    return false;
  }
  return typeof s.documentId === 'string' && s.documentId.length > 0;
}

/** 判断是否为业务实体来源：sourceType 命中五业务实体（CUSTOMER/BUSINESS_ACTIVITY/SALES_OPPORTUNITY/CONTRACT/CONTACT_TASK） */
function isBusinessSource(source: unknown): boolean {
  const s = (source ?? {}) as Record<string, unknown>;
  const type = (s.sourceType ?? s.type) as string | undefined;
  return typeof type === 'string' && BUSINESS_SOURCE_TYPES.includes(type);
}

/** 取出消息中所有 KB 来源 */
function kbSources(msg: ChatMessage): unknown[] {
  return (Array.isArray(msg.sources) ? msg.sources : []).filter(isKbSource);
}

/** 取出消息中所有业务实体来源 */
function businessSources(msg: ChatMessage): unknown[] {
  return (Array.isArray(msg.sources) ? msg.sources : []).filter(isBusinessSource);
}

/** 判断是否为图片切片来源：sourceType=IMAGE 且带资产 id（documentId=图片资产 id） */
function isImageSource(source: unknown): boolean {
  const s = (source ?? {}) as Record<string, unknown>;
  const type = (s.sourceType ?? s.type) as string | undefined;
  return type === IMAGE_SOURCE_TYPE && typeof s.documentId === 'string' && s.documentId.length > 0;
}

/** 图片缩略图的本地 blob URL（按资产 id 缓存）：后端从 token 头取身份，不能用裸 <img src> */
const imageThumbs = ref<Record<string, string>>({});
const imageThumbLoading = new Set<string>();

function imageThumbUrl(source: unknown): string {
  const s = (source ?? {}) as Record<string, unknown>;
  const assetId = typeof s.documentId === 'string' ? s.documentId : '';
  return assetId ? (imageThumbs.value[assetId] ?? '') : '';
}

/** 拉取图片资产（带 token 的 XHR → blob URL）；失败静默，标签行退化为纯文字 */
async function loadImageThumb(assetId: string): Promise<void> {
  if (!assetId || imageThumbs.value[assetId] || imageThumbLoading.has(assetId)) {
    return;
  }
  imageThumbLoading.add(assetId);
  try {
    const res = await axiosInstance.get(`/knowledge/images/${encodeURIComponent(assetId)}`, {
      responseType: 'blob',
    });
    const blob = res.data as Blob;
    imageThumbs.value = { ...imageThumbs.value, [assetId]: URL.createObjectURL(blob) };
  } catch {
    // 缩略图失败不影响答案正文：保持无图标签
  } finally {
    imageThumbLoading.delete(assetId);
  }
}

/** 新消息到达时为其中 IMAGE 来源预取缩略图 */
watch(
  chatMessages,
  (msgs) => {
    for (const msg of msgs) {
      for (const source of kbSources(msg)) {
        if (isImageSource(source)) {
          const assetId = (source as Record<string, unknown>).documentId;
          if (typeof assetId === 'string') {
            void loadImageThumb(assetId);
          }
        }
      }
    }
  },
  { immediate: true },
);

onUnmounted(() => {
  for (const url of Object.values(imageThumbs.value)) {
    URL.revokeObjectURL(url);
  }
  imageThumbs.value = {};
});

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

function businessSourceLabel(source: unknown): string {
  const s = (source ?? {}) as Record<string, unknown>;
  const filename = typeof s.filename === 'string' ? s.filename : '';
  const name = typeof s.name === 'string' ? s.name : '';
  return name || filename || '业务来源';
}

/** 打开 KB 来源：图片切片打开原图；文档来源进入文档查看抽屉并滚动到指定 chunk */
function openSource(source: unknown) {
  if (isImageSource(source)) {
    const thumb = imageThumbUrl(source);
    if (thumb) {
      window.open(thumb, '_blank');
    } else {
      antdMessage.info('图片加载中，请稍后再试');
    }
    return;
  }
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

/** 提取业务来源主键：剥离历史 documentId 前缀（如 CUSTOMER_COMPANY:301 → 301），兼容纯主键口径 */
function businessSourceId(source: unknown): string {
  const s = (source ?? {}) as Record<string, unknown>;
  const documentId = typeof s.documentId === 'string' ? s.documentId : '';
  const id = typeof s.id === 'string' || typeof s.id === 'number' ? String(s.id) : '';
  let result = id || documentId;
  for (const prefix of BUSINESS_DOC_ID_PREFIXES) {
    if (result.startsWith(prefix)) {
      result = result.slice(prefix.length);
      break;
    }
  }
  return result;
}

/** 业务实体来源深度跳转：客户/商机/合同/活动/任务逐类型精准路由，杜绝误调知识库接口 */
function jumpToBusiness(type: string, id: string) {
  if (!id) {
    antdMessage.warning('无效业务来源（无ID）');
    return;
  }
  if (type === 'CUSTOMER') {
    void router.push({ path: '/client/list', query: { 'filters.id': id } });
    return;
  }
  if (type === 'SALES_OPPORTUNITY') {
    void router.push(`/sale/detail/${id}`);
    return;
  }
  if (type === 'CONTRACT') {
    void router.push(`/sale/contract-details/${id}`);
    return;
  }
  if (type === 'BUSINESS_ACTIVITY') {
    void router.push(`/contact/activity?query=${encodeURIComponent(id)}`);
    return;
  }
  if (type === 'CONTACT_TASK') {
    void router.push({ path: '/contact/task', query: { id } });
    return;
  }
  antdMessage.warning(`未知业务来源类型: ${type}`);
}

/** 打开业务实体来源：按来源类型深度跳转 */
function openBusinessSource(source: unknown) {
  const s = (source ?? {}) as Record<string, unknown>;
  const type = (s.sourceType ?? s.type) as string | undefined;
  if (typeof type !== 'string' || !BUSINESS_SOURCE_TYPES.includes(type)) {
    antdMessage.warning('无效业务来源');
    return;
  }
  const id = businessSourceId(source);
  if (!id) {
    antdMessage.warning('无效业务来源（无ID）');
    return;
  }
  jumpToBusiness(type, id);
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
.source-tag .source-thumb {
  display: inline-block;
  width: 28px;
  height: 28px;
  margin-right: 4px;
  vertical-align: middle;
  object-fit: cover;
  border-radius: 4px;
  border: 1px solid rgba(0,0,0,0.08);
  background: #fff;
}
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
.pills-bar { display: flex; flex-wrap: wrap; gap: 6px; padding: 8px 8px 0; }
.starter-pill {
  border: 1px solid #d9d9d9;
  border-radius: 14px;
  padding: 2px 10px;
  font-size: 12px;
  color: #1677ff;
  background: #f5faff;
  cursor: pointer;
  transition: all 0.2s;
}
.starter-pill:hover { border-color: #1677ff; background: #e6f4ff; }
.input-bar { display: flex; gap: 8px; padding: 8px; border-top: 1px solid #f0f0f0; }
.streaming { color: #888; }
</style>
