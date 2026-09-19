<template>
  <div class="table-footer">
    <Pagination
      :total="total"
      :current="current"
      :page-size="pageSize"
      :show-size-changer="false"
      :show-quick-jumper="false"
    />
  </div>
</template>

<script setup lang="ts">
import { Pagination } from 'ant-design-vue';

defineProps<{
  total: number;
  current: number;
  pageSize: number;
}>();
</script>

<style scoped>
.table-footer {
  display: flex;
  justify-content: flex-end;
  padding: 16px 20px;
  background: #fff;
}
</style>
