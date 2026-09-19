<template>
  <!-- 客户管理 -->
  <div class="right-wrap">
    <div class="right-part">
      <slot></slot>
    </div>
  </div>
</template>

<script lang="ts">
export default {
  name: 'MainLayout',
};
</script>

<style scoped>
.right-wrap {
  width: 100%;
  min-height: 100%;
}
.right-part {
  padding: 0 20px;
  padding-top: 20px;
  width: 100%;
  overflow-x: auto;
}

@media (max-width: 768px) {
  .right-part {
    padding: 0;
  }
}
</style>
