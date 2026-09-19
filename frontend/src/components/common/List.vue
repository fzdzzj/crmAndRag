<template>
  <div class="permission-list">
    <div class="permission-list__title">
      <slot name="title" />
    </div>
    <ul class="permission-list__items">
      <li
        v-for="item in items"
        :key="item.id"
        class="permission-list__item"
      >
        <label class="permission-list__label">
          <input type="checkbox" :value="item.value" />
          <span>{{ item.label }}</span>
        </label>
      </li>
    </ul>
  </div>
</template>

<script setup lang="ts">
interface ListItem {
  id: string;
  value: string;
  label: string;
}

defineProps<{
  items: ListItem[];
}>();
</script>

<style scoped>
.permission-list {
  flex: 1;
}

.permission-list__title {
  margin-bottom: 12px;
  font-size: 14px;
}

.permission-list__items {
  margin: 0;
  padding: 0;
  list-style: none;
}

.permission-list__item + .permission-list__item {
  margin-top: 8px;
}

.permission-list__label {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  font-size: 14px;
  color: #303133;
}
</style>
