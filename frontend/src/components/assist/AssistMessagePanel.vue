<script setup lang="ts">
import { computed, ref } from 'vue';
import {
  Alert as AAlert,
  Button as AButton,
  Empty as AEmpty,
  Input as AInput,
  List as AList,
  ListItem as AListItem,
  Spin as ASpin,
  Tag as ATag,
  message,
} from 'ant-design-vue';
import { useMutation, useQuery, useQueryClient } from '@tanstack/vue-query';
import { axiosInstance } from '@/api/apiClient';

export type AssistMessage = {
  id?: number;
  senderName?: string;
  content?: string;
  messageType?: string;
  createTime?: string;
};

const props = defineProps<{ assistId?: number; assistStatus?: number }>();
const queryClient = useQueryClient();
const content = ref('');
const errorMessage = ref('');
const queryKey = computed(() => ['assist', 'messages', props.assistId]);
const isPending = computed(() => props.assistStatus === 0);

const query = useQuery({
  queryKey,
  enabled: computed(() => props.assistId != null),
  queryFn: async () => {
    const response = await axiosInstance.get(`/assist/${props.assistId}/messages`);
    return (response.data?.data ?? []) as AssistMessage[];
  },
});

const send = useMutation({
  mutationFn: async (value: string) => axiosInstance.post(`/assist/${props.assistId}/messages`, { content: value }),
  onSuccess: async () => {
    content.value = '';
    await queryClient.invalidateQueries({ queryKey: queryKey.value });
  },
});

const submit = async () => {
  const value = content.value.trim();
  if (!props.assistId || !value || !isPending.value || send.isPending.value) return;
  errorMessage.value = '';
  try {
    await send.mutateAsync(value);
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : '消息发送失败';
    message.error(errorMessage.value);
  }
};

const isSystem = (item: AssistMessage) => item.messageType?.toUpperCase() === 'SYSTEM';
</script>

<template>
  <section v-if="assistId" class="mt-4 border-t pt-4">
    <div class="mb-2 flex items-center justify-between">
      <h3 class="text-base font-medium">协助过程</h3>
      <ATag v-if="!isPending" color="default">只读</ATag>
    </div>
    <AAlert v-if="errorMessage" type="error" show-icon :message="errorMessage" class="mb-2" />
    <ASpin :spinning="query.isLoading.value">
      <AEmpty v-if="!query.data.value?.length" description="暂无协作消息" :image="AEmpty.PRESENTED_IMAGE_SIMPLE" />
      <AList v-else size="small" bordered>
        <AListItem v-for="item in query.data.value" :key="item.id ?? `${item.createTime}-${item.content}`">
          <div class="w-full">
            <div class="mb-1 flex flex-wrap items-center gap-2 text-xs text-gray-500">
              <ATag :color="isSystem(item) ? 'default' : 'blue'">{{ isSystem(item) ? '系统' : (item.senderName || '未知发送者') }}</ATag>
              <span>{{ item.createTime || '-' }}</span>
            </div>
            <div class="whitespace-pre-wrap text-sm text-gray-700">{{ item.content || '-' }}</div>
          </div>
        </AListItem>
      </AList>
    </ASpin>
    <div v-if="isPending" class="mt-3 flex items-end gap-2">
      <AInput.TextArea v-model:value="content" :rows="2" :maxlength="1000" placeholder="输入协作说明" @press-enter.exact.prevent="submit" />
      <AButton type="primary" :loading="send.isPending.value" :disabled="!content.trim()" @click="submit">发送</AButton>
    </div>
  </section>
</template>
