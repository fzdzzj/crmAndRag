<template>
  <!-- 立项签约，原来的静态页面 -->
  <div id="popover-container" class="container">
    <table class="projects-table">
      <thead>
        <tr>
          <th style="width: 30%">项目名称</th>
          <th style="width: 35%">上传资料</th>
          <th style="width: 35%">项目状态</th>
        </tr>
      </thead>
      <tbody>
        <!-- 项目1 -->
        <!-- <tr>
                    <td>
                        <div class="name-container">
                            <div class="project-name">项目1</div>
                             <a-popover placement="right" trigger="click">
                                <template #content>
                                    <p>1111</p>
                                    <p>1111</p>
                                </template>
                                <template #title>
                                    <span>1111</span>
                                </template>
                                <span class="project-detail-link">详情</span>
                            </a-popover>
                        </div>
                    </td>
                    <td>
                        <div class="info-container">
                            <div class="upload-info">关于xxxx的xxxx</div>
                            <a-popover placement="right" trigger="click">
                                <template #content>
                                    <p>2222</p>
                                    <p>2222</p>
                                </template>
                                <template #title>
                                    <span>2222</span>
                                </template>
                                <span class="view-detail-btn">点击查看详情</span>
                            </a-popover>
                        </div>
                    </td>
                    <td>
                        <div class="status-container">
                            <span class="project-status status-agreed">已同意</span>
                            <a-popover placement="right" trigger="click">
                                <template #content>
                                    <p>3333</p>
                                    <p>3333</p>
                                </template>
                                <template #title>
                                    <span>3333</span>
                                </template>
                                <span class="status-detail-link">详情</span>
                            </a-popover>
                        </div>
                    </td>
                </tr> -->

        <!-- 项目2 -->
        <!-- <tr>
                    <td>
                        <div class="name-container">
                            <div class="project-name">项目2</div>
                            <span class="project-detail-link">详情</span>
                        </div>
                    </td>
                    <td>
                        <div class="info-container">
                            <div class="upload-info">关于xxxx的xxxx</div>
                            <span class="view-detail-btn">点击查看详情</span>
                        </div>
                    </td>
                    <td>
                        <div class="status-container">
                            <span class="project-status status-returned">被退回</span>
                            <span class="status-detail-link">详情</span>
                        </div>
                    </td>
                </tr> -->

        <!-- 使用v-for遍历项目 -->
        <tr v-for="project in projects" :key="project.id">
          <td>
            <div class="name-container">
              <div class="project-name">{{ project.name }}</div>
              <a-popover
                placement="rightBottom"
                trigger="click"
                :get-popup-container="getPopupContainer"
              >
                <template #content>
                  <p>{{ project.detail.content1 }}</p>
                  <p>{{ project.detail.content2 }}</p>
                </template>
                <template #title>
                  <span>{{ project.detail.title }}</span>
                </template>
                <span class="project-detail-link">详情</span>
              </a-popover>
            </div>
          </td>
          <td>
            <div class="info-container">
              <div class="upload-info">{{ project.uploadInfo }}</div>
              <a-popover
                placement="rightBottom"
                trigger="click"
                :get-popup-container="getPopupContainer"
              >
                <template #content>
                  <p>{{ project.uploadDetail.content1 }}</p>
                  <p>{{ project.uploadDetail.content2 }}</p>
                </template>
                <template #title>
                  <span>{{ project.uploadDetail.title }}</span>
                </template>
                <span class="view-detail-btn">点击查看详情</span>
              </a-popover>
            </div>
          </td>
          <td>
            <div class="status-container">
              <span
                class="project-status"
                :class="'status-' + project.status.type"
                >{{ project.status.text }}</span
              >
              <a-popover
                placement="rightBottom"
                trigger="click"
                :get-popup-container="getPopupContainer"
              >
                <template #content>
                  <p>{{ project.statusDetail.content1 }}</p>
                  <p>{{ project.statusDetail.content2 }}</p>
                </template>
                <template #title>
                  <span>{{ project.statusDetail.title }}</span>
                </template>
                <span class="status-detail-link">详情</span>
              </a-popover>
            </div>
          </td>
        </tr>
      </tbody>
    </table>
  </div>
</template>
<script lang="ts" setup>
const projects = [
  {
    name: '项目1',
    id: '001',
    detail: {
      title: '项目1详情',
      content1: '项目1的详细信息1',
      content2: '项目1的详细信息2',
    },
    uploadInfo: '关于项目1的上传资料',
    uploadDetail: {
      title: '上传资料详情',
      content1: '项目1上传资料详情1',
      content2: '项目1上传资料详情2',
    },
    status: {
      type: 'agreed',
      text: '已同意',
    },
    statusDetail: {
      title: '状态详情',
      content1: '项目1状态详情1',
      content2: '项目1状态详情2',
    },
  },
  {
    name: '项目2',
    id: '002',
    detail: {
      title: '项目2详情',
      content1: '项目2的详细信息1',
      content2: '项目2的详细信息2',
    },
    uploadInfo: '关于项目2的上传资料',
    uploadDetail: {
      title: '上传资料详情',
      content1: '项目2上传资料详情1',
      content2: '项目2上传资料详情2',
    },
    status: {
      type: 'returned',
      text: '被退回',
    },
    statusDetail: {
      title: '状态详情',
      content1: '项目2状态详情1',
      content2: '项目2状态详情2',
    },
  },
  // 可以继续添加更多项目...
];

//改变点位，修改所有Popover组件，统一挂载到指定容器
//默认渲染在body中，固定定位
const getPopupContainer = (_triggerNode: HTMLElement) => {
  return document.getElementById('popover-container') || document.body;
};
//getPopupContainer 指向最外层不滚动的容器
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
  /* box-shadow: 0 2px 10px rgba(0, 0, 0, 0.05); */
  overflow: hidden;
  position: relative;
  /* 改变默认定位的元素，变成定位在这个元素上 */
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
  margin-right: 10px;
}

.project-detail-link {
  color: #3498db;
  text-decoration: none;
  cursor: pointer;
  font-size: 12px;
  display: inline-block;
  /* position: relative; */
}

.project-detail-link:hover {
  text-decoration: underline;
}

.upload-info {
  font-size: 16px;
  margin-right: 10px;
}

.status-container,
.info-container,
.name-container {
  display: flex;
  align-items: center;
}

.project-status {
  padding: 5px 12px;
  border-radius: 4px;
  font-size: 16px;
  font-weight: 500;
  margin-right: 10px;
}

.status-agreed {
  background-color: #e8f5e9;
  color: #2e7d32;
}

.status-returned {
  background-color: #ffebee;
  color: #c62828;
}

.status-modify {
  background-color: #fff3e0;
  color: #ef6c00;
}

.status-reviewing {
  background-color: #e3f2fd;
  color: #1565c0;
}

.status-detail-link {
  color: #3498db;
  cursor: pointer;
  font-size: 12px;
  display: inline-block;
}

.status-detail-link:hover {
  text-decoration: underline;
}

.view-detail-btn {
  color: #3498db;
  border: none;
  border-radius: 4px;
  cursor: pointer;
  font-size: 12px;
  display: inline-block;
}

.view-detail-btn:hover {
  text-decoration: underline;
}
</style>
