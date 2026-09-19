<template>
  <div :style="containerStyles">
    <div :style="{ display: 'flex', gap: '8px' }">
      <DatePicker
        v-model:value="startValue"
        format="YYYY-MM-DD"
        placeholder="开始日期"
        allow-clear
        :style="{ width: '100%' }"
      />
      <DatePicker
        v-model:value="endValue"
        format="YYYY-MM-DD"
        placeholder="结束日期"
        allow-clear
        :style="{ width: '100%' }"
      />
    </div>
    <div :style="buttonStyles">
      <Button type="primary" @click="handleConfirm">确认</Button>
      <Button @click="handleReset">重置</Button>
    </div>
  </div>
</template>

<script lang="ts" setup>
import { Button, DatePicker, message } from 'ant-design-vue';
import type { Dayjs } from 'dayjs';
import dayjs from 'dayjs';
import { computed, ref, watch, type CSSProperties } from 'vue';

interface Props {
  columnTitle: string;
  minValue?: string;
  maxValue?: string;
  styles?: {
    container?: CSSProperties;
    button?: CSSProperties;
  };
}

interface Emits {
  (e: 'confirm', min?: string, max?: string): void;
  (e: 'reset'): void;
  (e: 'close'): void;
}

const props = withDefaults(defineProps<Props>(), {
  minValue: undefined,
  maxValue: undefined,
});

const defaultContainerStyles: CSSProperties = {
  maxWidth: '360px',
  display: 'flex',
  flexDirection: 'column',
  gap: '8px',
  padding: '8px',
};
const defaultButtonStyles: CSSProperties = {
  display: 'flex',
  justifyContent: 'flex-end',
  gap: '8px',
};
const containerStyles = computed(() => ({
  ...defaultContainerStyles,
  ...(props.styles?.container || {}),
}));
const buttonStyles = computed(() => ({
  ...defaultButtonStyles,
  ...(props.styles?.button || {}),
}));

const emit = defineEmits<Emits>();

const startValue = ref<Dayjs | undefined>(undefined);
const endValue = ref<Dayjs | undefined>(undefined);

watch(
  () => [props.minValue, props.maxValue],
  ([min, max]) => {
    startValue.value = min ? dayjs(min) : undefined;
    endValue.value = max ? dayjs(max) : undefined;
  },
  { immediate: true },
);

const formatStart = (value: Dayjs | undefined) =>
  value ? value.startOf('day').format('YYYY-MM-DD HH:mm:ss') : undefined;
const formatEnd = (value: Dayjs | undefined) =>
  value ? value.endOf('day').format('YYYY-MM-DD HH:mm:ss') : undefined;

const handleConfirm = () => {
  if (!startValue.value && !endValue.value) {
    message.warning(`请选择${props.columnTitle}范围`);
    return;
  }
  emit('confirm', formatStart(startValue.value), formatEnd(endValue.value));
};

const handleReset = () => {
  startValue.value = undefined;
  endValue.value = undefined;
  emit('reset');
};
</script>
