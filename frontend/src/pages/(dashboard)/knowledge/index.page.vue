<script setup lang="ts">
import { ref, computed, watch } from 'vue';
import { useKnowledgeBases, useKnowledgeFiles, useKnowledgeUpload, useKnowledgeFileRemove, useKnowledgeReingest, useRetrievalTest, type KnowledgeFileVO, type KnowledgeAdminRetrievalResponse } from '@/hooks/useKnowledge';
import { Modal, message } from 'ant-design-vue';

const selectedKbId = ref<number | null>(null);
const query = ref('');
const topK = ref(5);
const useVector = ref(false); // 页面明示默认 false = 零外呼

const basesQuery = useKnowledgeBases();
const filesQuery = useKnowledgeFiles(selectedKbId);

const uploadMutation = useKnowledgeUpload();
const removeMutation = useKnowledgeFileRemove();
const reingestMutation = useKnowledgeReingest();
const retrievalMutation = useRetrievalTest();

const bases = computed(() => basesQuery.data.value || []);
const files = computed(() => filesQuery.data.value || []);

const selectedBase = computed(() => bases.value.find(b => b.id === selectedKbId.value));

// 上传相关
const selectedFile = ref<File | null>(null);
const uploadKbId = ref<number | null>(null);
const fileInput = ref<HTMLInputElement | null>(null);

function triggerFilePick() {
  fileInput.value?.click();
}

function onFileSelect(e: Event) {
  const target = e.target as HTMLInputElement;
  if (target.files?.[0]) selectedFile.value = target.files[0];
}

function onDrop(e: DragEvent) {
  e.preventDefault();
  if (e.dataTransfer?.files?.[0]) {
    selectedFile.value = e.dataTransfer.files[0];
  }
}

function doUpload() {
  if (!selectedFile.value || !uploadKbId.value) {
    message.warning('请选择文件和知识库');
    return;
  }
  uploadMutation.mutate({ file: selectedFile.value, kbId: uploadKbId.value }, {
    onSuccess: () => {
      selectedFile.value = null;
      // 触发 files 刷新已在 hook onSuccess
    }
  });
}

// 删除确认弹窗
function confirmDelete(file: KnowledgeFileVO) {
  Modal.confirm({
    title: '确认删除',
    content: `确定删除文档 "${file.originalFilename}" 吗？此操作不可恢复。`,
    okText: '删除',
    cancelText: '取消',
    onOk: () => removeMutation.mutate(String(file.id)),
  });
}

// 重建确认弹窗
function confirmReingest(file: KnowledgeFileVO) {
  Modal.confirm({
    title: '确认重建',
    content: `确定对 "${file.originalFilename}" 重新摄取吗？将产生额外 token 成本。`,
    okText: '重建',
    cancelText: '取消',
    onOk: () => reingestMutation.mutate(String(file.id)),
  });
}

// 检索
const retrievalResult = ref<KnowledgeAdminRetrievalResponse | null>(null);

function doRetrievalTest() {
  if (!query.value.trim()) {
    message.warning('请输入查询文本');
    return;
  }
  if (useVector.value && selectedKbId.value == null) {
    message.warning('真向量检索必须先选择一个知识库');
    return;
  }
  if (useVector.value && (!Number.isInteger(topK.value) || topK.value < 1 || topK.value > 10)) {
    message.warning('真向量检索 topK 必须是 1-10 的整数');
    return;
  }
  retrievalMutation.mutate({
    kbId: selectedKbId.value,
    query: query.value.trim(),
    topK: topK.value,
    useVector: useVector.value,
  }, {
    onSuccess: (data) => {
      retrievalResult.value = data;
    }
  });
}

function resetRetrieval() {
  query.value = '';
  retrievalResult.value = null;
}

// 明示默认零外呼
const retrievalHint = computed(() => 
  useVector.value 
    ? '真向量检索：须选择单个知识库；服务端默认关闭，开启后会产生模型调用费用'
    : '默认零外呼：稀疏检索（BM25），不触发 embedding 模型'
);

watch(selectedKbId, () => {
  retrievalResult.value = null;
});
</script>

<template>
  <div class="p-6 space-y-8 bg-white min-h-screen">
    <div class="flex items-center justify-between">
      <h1 class="text-2xl font-semibold">知识库管理</h1>
      <div class="text-sm text-gray-500">仅管理员可见 · 读写同权</div>
    </div>

    <!-- KB 选择器 -->
    <div class="border rounded-lg p-4">
      <div class="font-medium mb-2">知识库（按可见范围过滤）</div>
      <div class="flex flex-wrap gap-2">
        <button
          v-for="b in bases"
          :key="b.id"
          class="px-3 py-1 text-sm rounded border"
          :class="selectedKbId === b.id ? 'bg-blue-600 text-white border-blue-600' : 'hover:bg-gray-50'"
          @click="selectedKbId = b.id"
        >
          {{ b.displayName || b.name }}
        </button>
        <button class="px-3 py-1 text-sm rounded border hover:bg-gray-50" :class="!selectedKbId ? 'bg-gray-100' : ''" @click="selectedKbId = null">全部可见</button>
      </div>
      <div v-if="selectedBase" class="mt-1 text-xs text-gray-500">{{ selectedBase.name }} · {{ selectedBase.visibility }}</div>
    </div>

    <!-- 文档表格 -->
    <div class="border rounded-lg overflow-hidden">
      <div class="px-4 py-3 bg-gray-50 flex items-center justify-between border-b">
        <div class="font-medium">文档列表</div>
        <button class="text-sm px-3 py-1 border rounded hover:bg-white" @click="filesQuery.refetch()">刷新</button>
      </div>
      <table class="w-full text-sm">
        <thead class="bg-gray-50">
          <tr>
            <th class="text-left p-3">文件名</th>
            <th class="text-left p-3">状态</th>
            <th class="text-right p-3">Chunk</th>
            <th class="text-right p-3">向量</th>
            <th class="text-left p-3">摄取时间</th>
            <th class="text-right p-3 w-40">操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="f in files" :key="f.id" class="border-t hover:bg-gray-50">
            <td class="p-3 font-mono text-xs truncate max-w-[280px]">{{ f.originalFilename }}</td>
            <td class="p-3">
              <span
                class="px-2 py-0.5 text-xs rounded"
                :class="{
                  'bg-green-100 text-green-700': f.status === 'COMPLETED',
                  'bg-yellow-100 text-yellow-700': f.status === 'INGESTING' || f.status === 'PENDING',
                  'bg-red-100 text-red-700': f.status === 'FAILED'
                }"
              >{{ f.status }}</span>
            </td>
            <td class="p-3 text-right font-mono">{{ f.segmentCount ?? 0 }}</td>
            <td class="p-3 text-right font-mono">{{ f.vectorCount ?? 0 }}</td>
            <td class="p-3 text-gray-500 text-xs">{{ f.createTime }}</td>
            <td class="p-3 text-right space-x-2">
              <button class="text-blue-600 hover:underline text-xs" @click="confirmReingest(f)">重建</button>
              <button class="text-red-600 hover:underline text-xs" @click="confirmDelete(f)">删除</button>
            </td>
          </tr>
          <tr v-if="!files.length && !filesQuery.isLoading.value">
            <td colspan="6" class="p-8 text-center text-gray-400">暂无文档</td>
          </tr>
        </tbody>
      </table>
      <div v-if="filesQuery.isLoading.value" class="p-3 text-xs text-gray-500">加载中...</div>
    </div>

    <!-- 上传 -->
    <div class="border rounded-lg p-4 grid grid-cols-1 md:grid-cols-2 gap-4">
      <div>
        <div class="font-medium mb-2">上传文档</div>
        <select v-model="uploadKbId" class="border rounded px-3 py-1 w-full mb-2">
          <option :value="null">选择知识库</option>
          <option v-for="b in bases" :key="b.id" :value="b.id">{{ b.displayName || b.name }}</option>
        </select>

        <div
          class="border-2 border-dashed rounded p-6 text-center cursor-pointer hover:border-blue-400"
          @drop="onDrop"
          @dragover.prevent
          @dragenter.prevent
          @click="triggerFilePick"
        >
          <div class="text-gray-500">拖拽文件到此处，或点击选择</div>
          <input ref="fileInput" type="file" class="hidden" @change="onFileSelect" />
          <div v-if="selectedFile" class="mt-2 text-sm font-mono truncate">{{ selectedFile.name }}</div>
        </div>
      </div>
      <div class="flex items-end">
        <button
          :disabled="uploadMutation.isPending.value || !selectedFile || !uploadKbId"
          class="px-6 py-2 bg-blue-600 text-white rounded disabled:bg-gray-300"
          @click="doUpload"
        >
          {{ uploadMutation.isPending.value ? '上传中...' : '上传并摄取' }}
        </button>
        <div class="ml-3 text-xs text-gray-500">摄取为异步，上传后状态会更新</div>
      </div>
    </div>

    <!-- 检索测试面板 -->
    <div class="border rounded-lg p-4">
      <div class="font-medium mb-1">检索测试（dry-run）</div>
      <div class="text-xs text-amber-600 mb-3">明示：默认零外呼（稀疏检索 / useVector=false），不触发 embedding 模型。仅授权节点可切换真向量。</div>

      <div class="flex gap-2 mb-3 flex-wrap items-center">
        <input v-model="query" placeholder="输入查询文本..." class="border rounded px-3 py-1 flex-1 min-w-[200px]" />
        <input v-model.number="topK" type="number" class="border rounded px-2 py-1 w-20" />
        <label class="flex items-center gap-1 text-sm">
          <input v-model="useVector" type="checkbox" /> useVector
        </label>
        <button :disabled="retrievalMutation.isPending.value" class="px-4 py-1 bg-emerald-600 text-white rounded text-sm" @click="doRetrievalTest">
          {{ retrievalMutation.isPending.value ? '测试中...' : '测试检索' }}
        </button>
        <button class="px-3 py-1 text-sm border rounded" @click="resetRetrieval">清空</button>
      </div>

      <div class="text-xs text-gray-500 mb-2">{{ retrievalHint }}</div>

      <div v-if="retrievalResult" class="bg-gray-50 p-3 rounded text-sm">
        <div class="font-mono mb-2">query: {{ retrievalResult.query }} | usedVector: {{ retrievalResult.usedVector }} | topK: {{ retrievalResult.topK }}</div>
        <div v-for="(c, idx) in retrievalResult.candidates" :key="idx" class="mb-2 p-2 bg-white border rounded">
          <div class="text-xs text-gray-500">score: {{ c.score.toFixed(4) }} | {{ c.filename || c.chunkId }}</div>
          <div class="mt-1 text-gray-700">{{ c.text }}</div>
        </div>
        <div v-if="!retrievalResult.candidates?.length" class="text-gray-400">无候选结果</div>
      </div>
      <div v-else class="text-gray-400 text-sm">输入 query 后点击测试，查看候选 chunk + 分数</div>
    </div>

    <div class="text-[10px] text-gray-400">前端仅消费 /knowledge 7 端点 · Tailwind-only · 默认零外呼</div>
  </div>
</template>
