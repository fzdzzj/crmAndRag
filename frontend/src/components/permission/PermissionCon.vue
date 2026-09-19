<template>
  <!-- 数据列表区域 -->
  <div class="data-table-section">
    <div class="table-header">
      <div class="table-title"><slot name="title"></slot></div>
      <div class="table-header-btn">
        <slot name="btn-one"></slot>
        <slot name="btn-two"></slot>
      </div>
    </div>
    <div class="table-footer">
      <List :items="dataItems">
        <template #title>
          <span>数据管理</span>
        </template>
      </List>
      <List :items="clientItems">
        <template #title>
          <span>客户管理</span>
        </template>
      </List>
      <List :items="systemItems">
        <template #title>
          <span>系统设置</span>
        </template>
      </List>
      <List :items="reportItems">
        <template #title>
          <span>报表管理</span>
        </template>
      </List>
    </div>
  </div>
</template>

<script lang="ts">
import List from '@/components/common/List.vue';

export default {
  components: {
    List,
  },
  data() {
    return {
      dataItems: [
        { id: '1', value: 'view_data', label: '查看数据' },
        { id: '2', value: 'edit_data', label: '编辑数据' },
        { id: '3', value: 'delete_data', label: '删除数据' },
      ],
      clientItems: [
        { id: '1', value: 'view_client', label: '查看客户' },
        { id: '2', value: 'edit_client', label: '编辑客户' },
        { id: '3', value: 'delete_client', label: '删除客户' },
      ],
      systemItems: [
        { id: '1', value: 'basic_setting', label: '基础设置' },
        { id: '2', value: 'security_setting', label: '安全设置' },
        { id: '3', value: 'advanced_setting', label: '高级设置' },
      ],
      reportItems: [
        { id: '1', value: 'view_report', label: '查看报表' },
        { id: '2', value: 'export_report', label: '导出报表' },
        { id: '3', value: 'analyze_report', label: '分析报表' },
      ],
    };
  },
};
</script>

<style scoped>
.data-table-section {
  background: #fff;
  border: 1px solid #ebeef5;
  border-radius: 8px;
  padding: 25.3125px;
  margin: 0 auto;
  margin-top: 20px;
}

/* 表头的样式 */
.table-header {
  width: 1104px;
  height: 40.08px;
  margin-bottom: 25.313px;
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.table-footer {
  display: flex;
  justify-content: space-between;
}

.table-title {
  font-size: 16px;
  font-weight: bold;
  color: #303133;
}

.table-header-btn {
  height: 40.08px;
  /* width: 208.25px; */
  gap: 12px;
  display: flex;
  justify-content: space-between;
  align-items: center;
  font-size: 14px;
}

.table-choose {
  width: 84px;
  height: 100%;
  border: 1px solid #000;
  border-radius: 3px;
  text-align: center;
  padding: 11px 0;
  color: #6b7280;
}
.table-create {
  width: 108.25px;
  height: 37.95px;
  border-radius: 3px;
  text-align: center;
  padding: 10px 0;
  background-color: #3176ff;
  color: #fff;
}
span {
  font-weight: 800;
}
</style>
