<template>
  <!-- 我的合同表格，原来的静态页面 -->
  <div id="popover-container" class="container">
    <table class="projects-table">
      <thead>
        <tr>
          <th style="width: 20%">合同编号</th>
          <th style="width: 20%">关联项目</th>
          <th style="width: 20%">付款周期</th>
          <th style="width: 20%">开始时间</th>
          <th style="width: 20%">截止时间</th>
        </tr>
      </thead>
      <tbody>
        <!-- 遍历合同项 -->
        <tr v-for="contract in contracts" :key="contract.id">
          <td>
            <div class="name-container">
              <div class="project-name">{{ contract.name }}</div>
            </div>
          </td>
          <td>
            <div class="info-container">
              <div class="upload-info">{{ contract.project }}</div>
              <span class="view-detail-btn">详情</span>
            </div>
          </td>
          <td>
            <div class="status-container">
              <span class="project-status">{{ contract.payment }}</span>
              <!-- 动态绑定路径，路径隐藏信息 -->
              <router-link
                :to="`/sale/contract/payment-details/${contract.id}`"
              >
                <span class="status-detail-link">详情</span>
              </router-link>
            </div>
          </td>
          <td>
            <div class="date-container">{{ contract.startDate }}</div>
          </td>
          <td>
            <div class="date-container">
              <span>{{ contract.endDate }}</span>
              <!-- <span class="status-detail-link overall-details">详情</span> -->
              <router-link
                :to="`/sale/contract/contract-details/${contract.id}`"
              >
                <span class="status-detail-link overall-details">详情</span>
              </router-link>
            </div>
          </td>
        </tr>
      </tbody>
    </table>
    <TableFooter :total="10" :current="1" :page-size="5" />
  </div>

  <!-- 确保子路由的父亲路由的组件里面包含router-view视图  -->
  <!-- <router-view></router-view> -->
</template>

<script setup lang="ts">
import TableFooter from '@/components/common/TableFooter.vue';

const contracts = [
  {
    id: '1',
    name: '合同1',
    project: '项目1',
    payment: '全款',
    startDate: '2025/09/01',
    endDate: '2027/09/01',
  },
  {
    id: '2',
    name: '合同2',
    project: '项目2',
    payment: '分3期',
    startDate: '2025/09/01',
    endDate: '2027/09/01',
  },
  {
    id: '3',
    name: '合同3',
    project: '项目3',
    payment: '分5期',
    startDate: '2025/10/01',
    endDate: '2027/10/01',
  },
  // 可以继续添加更多合同数据
];
</script>

<style scoped>
* {
  margin: 0;
  padding: 0;
  box-sizing: border-box;
  font-family: 'Segoe UI', 'Microsoft YaHei', sans-serif;
}

.container {
  max-width: 1200px;
  margin: 0 auto;
  background: white;
  border-radius: 8px;
  overflow: hidden;
  position: relative;
}

.projects-table {
  width: 100%;
  border-collapse: collapse;
}

.projects-table th {
  padding: 15px 20px;
  text-align: left;
  font-weight: 600;
  font-size: 16px;
  border-bottom: 1px solid #e2e8f0;
}

.projects-table td {
  padding: 15px 20px;
  border-bottom: 1px solid #e2e8f0;
}

.project-name {
  font-weight: 500;
  font-size: 16px;
}

.upload-info {
  font-size: 16px;
  margin-right: 10px;
}

.status-container,
.info-container,
.name-container,
.date-container {
  display: flex;
  align-items: center;
}

.project-status {
  padding: 5px 12px;
  border-radius: 4px;
  font-size: 16px;
  font-weight: 500;
  margin-right: 10px;
  background-color: #e3f2fd;
  color: #1565c0;
}

.view-detail-btn,
.status-detail-link {
  color: #3498db;
  cursor: pointer;
  font-size: 12px;
  display: inline-block;
  margin-left: 8px;
}
.overall-details {
  margin-left: 35px;
  font-size: 16px;
}

.view-detail-btn:hover,
.status-detail-link:hover {
  text-decoration: underline;
}
</style>
