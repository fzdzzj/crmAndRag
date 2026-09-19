<template>
  <div :style="containerStyles">
    <InputNumber
      v-model:value="inputValue"
      :placeholder="placeholder"
      :style="{
        width: '100%',
      }"
      @press-enter="handleConfirm"
    >
    </InputNumber>
    <div :style="buttonStyles">
      <Button type="primary" @click="handleConfirm">确认</Button>
      <Button @click="handleReset">重置</Button>
    </div>
  </div>
</template>

<script lang="ts" setup>
import { Button } from 'ant-design-vue';
import { InputNumber } from 'ant-design-vue';
import { computed, ref, watch, type CSSProperties } from 'vue';

interface Props {
  columnTitle: string;
  filterValue?: number;
  styles?: {
    container?: CSSProperties;
    button?: CSSProperties;
  };
}

interface Emits {
  (e: 'confirm', value: Props['filterValue']): void;
  (e: 'reset'): void;
  (e: 'close'): void;
}

const props = withDefaults(defineProps<Props>(), {
  filterValue: undefined,
});
const defaultContainerStyles: CSSProperties = {
  maxWidth: '300px',
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

const inputValue = ref<Props['filterValue']>(props.filterValue);

const placeholder = computed(() => `请输入${props.columnTitle}`);

const handleConfirm = () => {
  emit('confirm', inputValue.value);
};

const handleReset = () => {
  inputValue.value = undefined;
  emit('reset');
};

watch(
  () => props.filterValue,
  (newValue) => {
    inputValue.value = newValue;
  },
);
</script>
