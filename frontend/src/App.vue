<template>
  <ConfigProvider :locale="locale">
    <div id="app">
      <router-view></router-view>
      <!-- 评审工具只在 DEV 挂载：生产构建里 ReviewTool 恒为 null，v-if 直接短路，
           组件与它的 style.css 都不进产物（见 script 里的 DEV 门控说明） -->
      <ReviewTool
        v-if="ReviewTool"
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
import { defineAsyncComponent, onMounted, ref } from 'vue';
import { API_BASE_URL } from '@/api/config';

// 原来是静态 `import { ReviewTool } from 'vue-page-review'` + 顶层 `import 'vue-page-review/style.css'`：
// App.vue 是全量入口的根组件，这样写等于把评审工具（含其 CSS）永久钉进生产包并常驻挂载点，
// 而它只在本地开发收集页面反馈时用得到。
// 改成 import.meta.env.DEV 门控的动态 import：Vite 在 build 时把 import.meta.env.DEV 静态替换为 false，
// 整个三元左支（连同里面两个 import()）被 Rollup DCE 掉 —— 包不进 module graph、chunk 不生成、
// 运行时也没有挂载节点。dev 侧行为不变：首次渲染即触发异步加载，v-model:active 与两个 props 原样传。
const ReviewTool = import.meta.env.DEV
  ? defineAsyncComponent(async () => {
      const mod = await import('vue-page-review');
      await import('vue-page-review/style.css');
      return mod.ReviewTool;
    })
  : null;

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
