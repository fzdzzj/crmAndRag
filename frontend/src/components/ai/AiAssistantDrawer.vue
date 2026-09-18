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
      <div class="messages" ref="messagesRef">
        <div v-for="(msg, idx) in chatMessages" :key="idx" class="msg" :class="msg.role">
          <div class="role">{{ msg.role === 'user' ? '你' : '助手' }}</div>
          <div class="content">
            <pre v-if="msg.msgType === 'actionCard' || msg.msgType === 'draftProgress'">{{ msg.content || JSON.stringify(msg.payload, null, 2) }}</pre>
            <div v-else v-html="formatContent(msg.content)"></div>

            <!-- references 支持（真实下发） -->
            <div v-if="msg.references && msg.references.length" class="refs">
              <Tag v-for="(ref, i) in msg.references" :key="i" color="blue" @click="jumpReference(ref)">
                {{ refLabel(ref) }}{{ refIdSuffix(ref) }}
              </Tag>
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
          @pressEnter="send"
          :disabled="isStreaming"
        />
        <Button :loading="isStreaming" @click="send">发送</Button>
        <Button v-if="isStreaming" @click="chat.stop">停止</Button>
        <Button @click="toggleKb">{{ useKb ? 'KB:开' : 'KB:关' }}</Button>
      </div>

      <div v-if="chat.error" class="error">{{ chat.error }}</div>
    </div>
  </Drawer>
</template>

<script setup lang="ts">
import { ref, watch, nextTick, computed } from 'vue';
import { Drawer, Button, Input, Tag, message as antdMessage } from 'ant-design-vue';
import { useAiChat, type ChatMessage } from '@/hooks/useAiChat';
import { useAiSessions, useCreateAiSession, useDeleteAiSession, useAiSessionMessages } from '@/hooks/useAiSession';
import { useAiAction } from '@/hooks/useAiAction';

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
const { sessions, refetch: refetchSessions } = useAiSessions();
const createSession = useCreateAiSession();
const deleteSessionMut = useDeleteAiSession();
const action = useAiAction();

const sessionMessages = useAiSessionMessages(computed(() => currentSessionId.value || null));

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

function formatContent(c: string) {
  return (c || '').replace(/\n/g, '<br/>');
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
  } catch {
    antdMessage.error('创建会话失败');
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
    await refetchSessions();
  } catch {
    /* 删除失败静默：列表刷新兜底 */
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
.action-card { border: 1px dashed #faad14; padding: 4px; margin-top: 4px; font-size: 12px; }
.input-bar { display: flex; gap: 8px; padding: 8px; border-top: 1px solid #f0f0f0; }
.error { color: red; padding: 4px; font-size: 12px; }
.streaming { color: #888; }
</style>
