<template>
  <div class="flex flex-wrap items-center gap-2 text-xs text-red-500">
    <span>{{ message }}</span>
    <template v-if="!voted">
      <Button size="small" @click="report(true)">有用</Button>
      <Button size="small" @click="report(false)">无用</Button>
    </template>
    <span v-else class="text-neutral-400">感谢反馈</span>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue';
import { Button } from 'ant-design-vue';
import { logErrorFeedback } from '@/utils/log';

const props = defineProps<{ code: string; message: string }>();

const voted = ref(false);

function report(helpful: boolean) {
  if (voted.value) return;
  voted.value = true;
  logErrorFeedback(props.code, helpful);
}
</script>
