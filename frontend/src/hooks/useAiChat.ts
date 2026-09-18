import { ref, computed, onUnmounted } from 'vue';
import { axiosInstance } from '@/api/apiClient';
import { message as antdMessage } from 'ant-design-vue';
import { TokenManager } from '@/utils/token';

export interface ChatMessage {
  id?: string | number;
  role: 'user' | 'assistant' | 'system';
  content: string;
  msgType?: string;
  payload?: unknown;
  references?: unknown[];
  createdTime?: string;
  interrupted?: boolean;
}

export interface SseEvent {
  event: string;
  data: Record<string, unknown> | string | null;
}

const KNOWN_EVENTS = ['start', 'meta', 'sources', 'thinking', 'delta', 'references', 'title', 'done', 'cancelled', 'stopped', 'error', 'ping'] as const;

function isKnownEvent(name: string): boolean {
  return (KNOWN_EVENTS as readonly string[]).includes(name);
}

/**
 * 跨 chunk 缓冲 SSE 解析器
 * 处理不完整 event/data 行
 */
async function* parseSSEStream(reader: ReadableStreamDefaultReader<Uint8Array>): AsyncGenerator<SseEvent> {
  const decoder = new TextDecoder('utf-8');
  let buffer = '';
  let currentEvent: string | null = null;
  let currentDataLines: string[] = [];

  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;

      buffer += decoder.decode(value, { stream: true });

      const lines = buffer.split(/\r?\n/);
      buffer = lines.pop() || ''; // 保留不完整行

      for (const line of lines) {
        if (line === '') {
          // 事件结束
          if (currentEvent && currentDataLines.length > 0) {
            const dataStr = currentDataLines.join('\n');
            let data: SseEvent['data'] = dataStr;
            if (dataStr) {
              try {
                data = JSON.parse(dataStr) as Record<string, unknown>;
              } catch {
                data = dataStr;
              }
            }
            if (isKnownEvent(currentEvent)) {
              yield { event: currentEvent, data };
            } else {
              // 降级：未知事件（包括 actionCard/draftProgress 如果以事件形式下发）忽略，不中断流
              console.debug('[SSE] ignored unknown event:', currentEvent);
            }
          }
          currentEvent = null;
          currentDataLines = [];
          continue;
        }

        if (line.startsWith('event:')) {
          currentEvent = line.slice(6).trim();
        } else if (line.startsWith('data:')) {
          currentDataLines.push(line.slice(5).trimStart());
        } else if (line.startsWith('id:')) {
          // 可用于断线续传，当前简化忽略
        } else if (line.startsWith(':')) {
          // comment ping
          if (line.includes('ping')) {
            yield { event: 'ping', data: null };
          }
        }
      }
    }

    // flush 剩余 buffer
    if (buffer.trim() && currentEvent) {
      // 类似处理
      const dataStr = currentDataLines.join('\n') + (buffer.startsWith('data:') ? buffer.slice(5).trimStart() : '');
      let data: SseEvent['data'] = dataStr;
      try {
        data = JSON.parse(dataStr || '{}') as Record<string, unknown>;
      } catch {
        /* 保留原始文本 */
      }
      if (isKnownEvent(currentEvent)) {
        yield { event: currentEvent, data };
      }
    }
  } finally {
    reader.releaseLock();
  }
}

export function useAiChat() {
  const messages = ref<ChatMessage[]>([]);
  const isStreaming = ref(false);
  const sessionId = ref<string | null>(null);
  const error = ref<string | null>(null);
  let abortCtrl: AbortController | null = null;
  let currentAssistantMsg: ChatMessage | null = null;

  function reset() {
    messages.value = [];
    isStreaming.value = false;
    sessionId.value = null;
    error.value = null;
    currentAssistantMsg = null;
    if (abortCtrl) {
      abortCtrl.abort();
      abortCtrl = null;
    }
  }

  function addMessage(msg: Partial<ChatMessage>) {
    const full: ChatMessage = {
      role: 'assistant',
      content: '',
      ...msg,
    };
    messages.value.push(full);
    return full;
  }

  async function sendMessage(text: string, opts: { useKnowledgeBase?: boolean; thinking?: boolean } = {}) {
    if (!text.trim() || isStreaming.value) return;

    error.value = null;
    isStreaming.value = true;

    // 添加用户消息
    messages.value.push({ role: 'user', content: text.trim() });

    // 添加占位 assistant
    currentAssistantMsg = addMessage({ role: 'assistant', content: '', msgType: 'text' });

    const body = {
      sessionId: sessionId.value,
      message: text.trim(),
      useKnowledgeBase: !!opts.useKnowledgeBase,
      thinking: !!opts.thinking,
      imageRef: null,
      attachments: [],
    };

    abortCtrl = new AbortController();

    try {
      const token = TokenManager.getAccessToken();
      const resp = await fetch('/ai/chat/stream', {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'token': token || '',
          'Accept': 'text/event-stream',
        },
        body: JSON.stringify(body),
        signal: abortCtrl.signal,
      });

      if (!resp.ok) {
        const txt = await resp.text().catch(() => '');
        throw new Error(`HTTP ${resp.status}: ${txt || resp.statusText}`);
      }

      const reader = resp.body!.getReader();
      const evtStr = (evt: SseEvent, field: string): string | null => {
        const d = evt.data;
        if (!d || typeof d !== 'object') return null;
        const v = d[field];
        return typeof v === 'string' || typeof v === 'number' ? String(v) : null;
      };

      for await (const evt of parseSSEStream(reader)) {
        if (!currentAssistantMsg) continue;
        const d = evt.data && typeof evt.data === 'object' ? evt.data : null;

        switch (evt.event) {
          case 'start': {
            const sid = evtStr(evt, 'sessionId');
            if (sid) {
              sessionId.value = sid;
            }
            break;
          }
          case 'meta':
            // 可记录 provider 等
            break;
          case 'delta': {
            const piece = evtStr(evt, 'content');
            if (piece) {
              currentAssistantMsg.content += piece;
            }
            break;
          }
          case 'thinking': {
            // 可单独处理思考，或追加到 content 带标记
            const piece = evtStr(evt, 'text');
            if (piece) {
              // 简化：追加到 content
              if (!currentAssistantMsg.content.includes('思考')) {
                currentAssistantMsg.content += '\n[思考] ';
              }
              currentAssistantMsg.content += piece;
            }
            break;
          }
          case 'references': {
            const refs = Array.isArray(d?.items) ? d?.items : Array.isArray(d) ? d : null;
            if (refs) currentAssistantMsg.references = refs;
            break;
          }
          case 'sources':
            // 可用于知识来源，当前合并到 references
            break;
          case 'title':
            // 标题异步，当前忽略或存
            break;
          case 'done': {
            isStreaming.value = false;
            const sid = evtStr(evt, 'sessionId');
            if (sid) sessionId.value = sid;
            currentAssistantMsg = null;
            break;
          }
          case 'error':
            error.value = evtStr(evt, 'msg') || '流式错误';
            antdMessage.error(error.value);
            isStreaming.value = false;
            currentAssistantMsg = null;
            break;
          case 'cancelled':
          case 'stopped':
            isStreaming.value = false;
            if (currentAssistantMsg) currentAssistantMsg.interrupted = true;
            currentAssistantMsg = null;
            break;
          case 'ping':
            // 忽略
            break;
          default:
            // 已由 parser 过滤未知
            break;
        }
      }
    } catch (e: unknown) {
      if ((e as Error)?.name === 'AbortError') {
        if (currentAssistantMsg) currentAssistantMsg.interrupted = true;
      } else {
        error.value = (e as Error)?.message || '发送失败';
        antdMessage.error(error.value);
      }
      isStreaming.value = false;
      currentAssistantMsg = null;
    } finally {
      isStreaming.value = false;
      abortCtrl = null;
    }
  }

  async function stop() {
    if (abortCtrl) {
      abortCtrl.abort();
    }
    if (sessionId.value) {
      try {
        await axiosInstance.post('/ai/chat/cancel', { sessionId: sessionId.value });
      } catch {
        // 忽略取消失败
      }
    }
    if (currentAssistantMsg) {
      currentAssistantMsg.interrupted = true;
    }
    isStreaming.value = false;
    currentAssistantMsg = null;
  }

  function loadHistory(history: ChatMessage[]) {
    reset();
    messages.value = history.map(h => ({ ...h }));
  }

  // 组件卸载清理
  onUnmounted(() => {
    if (abortCtrl) abortCtrl.abort();
  });

  return {
    messages: computed(() => messages.value),
    isStreaming: computed(() => isStreaming.value),
    sessionId: computed(() => sessionId.value),
    error: computed(() => error.value),
    sendMessage,
    stop,
    loadHistory,
    reset,
  };
}
