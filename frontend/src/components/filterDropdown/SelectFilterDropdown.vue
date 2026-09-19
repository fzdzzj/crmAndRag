<template>
  <div :style="containerStyles">
    <div :style="buttonStyles">
      <Button type="primary" @click="handleConfirm">确认</Button>
      <Button @click="handleReset">重置</Button>
    </div>
    <Select
      v-model:value="selectedValue"
      :placeholder="placeholder"
      mode="multiple"
      :options="normalizedOptions"
    >
      <Select.Option
        v-for="item in selectOptions"
        :key="item.value"
        :value="item.value"
      >
        {{ item.label }}
      </Select.Option>
    </Select>
  </div>
</template>

<script lang="ts" setup>
import { Button, Select } from 'ant-design-vue';
import { computed, ref, watch, type CSSProperties } from 'vue';
import type { RawValueType } from 'ant-design-vue/es/vc-select/BaseSelect';

interface FilterOption {
  label: string;
  value: string | number;
}

interface Props {
  selectOptions: FilterOption[];
  columnTitle: string;
  filterValue?: RawValueType[];
  styles?: {
    button?: CSSProperties;
    container?: CSSProperties;
  };
}

interface Emits {
  (e: 'confirm', value: Props['filterValue']): void;
  (e: 'reset'): void;
  (e: 'close'): void;
}
const defaultButtonStyles: CSSProperties = {
  display: 'flex',
  justifyContent: 'flex-end',
  gap: '8px',
};
const defaultContainerStyles: CSSProperties = {
  display: 'flex',
  flexDirection: 'column',
  gap: '8px',
  padding: '8px',
  maxWidth: '150px',
};
const containerStyles = computed(() => ({
  ...defaultContainerStyles,
  ...(props.styles?.container || {}),
}));

const buttonStyles = computed(() => ({
  ...defaultButtonStyles,
  ...(props.styles?.button || {}),
}));

const props = withDefaults(defineProps<Props>(), {
  filterValue: () => [],
  styles: () => ({}),
});

const emit = defineEmits<Emits>();

const selectedValue = ref<Props['filterValue']>(props.filterValue);

const placeholder = computed(() => `请选择${props.columnTitle}`);

const normalizedOptions = computed(() =>
  props.selectOptions.map((item) => ({
    label: item.label,
    value: item.value,
  })),
);

const handleConfirm = () => {
  emit('confirm', selectedValue.value);
};

const handleReset = () => {
  selectedValue.value = [];
  emit('reset');
};

watch(
  () => props.filterValue,
  (newValue) => {
    selectedValue.value = newValue || [];
  },
);
</script>
