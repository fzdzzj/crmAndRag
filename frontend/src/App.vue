<template>
  <ConfigProvider :locale="locale">
    <div id="app">
      <router-view></router-view>
      <ReviewTool
        v-model:active="reviewActive"
        :page-path="$route.path"
        :page-name="$route.name?.toString()"
      />
    </div>
  </ConfigProvider>
</template>
<script setup lang="ts">
import { ConfigProvider } from 'ant-design-vue';
import zhCN from 'ant-design-vue/es/locale/zh_CN';
import axios from 'axios';
import { onMounted, ref } from 'vue';
import { ReviewTool } from 'vue-page-review';
import 'vue-page-review/style.css';
import { API_BASE_URL } from '@/api/config';

onMounted(() => {
  // health check：/health 返回纯文本而非 Result 业务壳，故绕开 apiClient 的响应拦截器；
  // validateStatus 全放行，只要拿到响应（含 401）就算可达，网络层失败才 reject
  axios.get(`${API_BASE_URL}/health`, { validateStatus: () => true }).then(() => {
    console.log('API server is healthy');
  }).catch((err) => {
    console.error('API server is not reachable:', err);
  })
})

const locale = zhCN;
const reviewActive = ref(false);
</script>

<style>
#app {
  font-family: Avenir, Helvetica, Arial, sans-serif;
  -webkit-font-smoothing: antialiased;
  -moz-osx-font-smoothing: grayscale;
  color: #2c3e50;
  height: 100vh;
  width: 100vw;
  background-color: rgba(245, 245, 245, 1);
}

body {
  margin: 0;
  padding: 0;
  height: 100%;
  width: 100%;
  box-sizing: border-box;
}

/* 移动端：表格操作图标扩大触控区域，避免误触 */
@media (max-width: 768px) {
  .ant-table-tbody .anticon-eye,
  .ant-table-tbody .anticon-edit,
  .ant-table-tbody .anticon-delete,
  .ant-table-tbody .anticon-retweet,
  .ant-table-tbody .anticon-paper-clip {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    padding: 10px;
    font-size: 18px;
  }
}
</style>
