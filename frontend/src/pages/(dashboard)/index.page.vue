<template>
  <div class="home-dashboard">
    <!-- 统计卡片区（无 report 查看权限时隐藏） -->
    <template v-if="showStats">
      <div class="stats-section">
        <div class="section-header">
          <h2>本月数据概览</h2>
          <span class="section-sub">统计区间：本月 1 日 ~ 今天</span>
        </div>
        <div class="stat-card-grid">
          <div v-for="card in statisticCards" :key="card.key" class="stat-card">
            <div class="stat-label">{{ card.label }}</div>
            <div class="stat-value">{{ card.value }}</div>
          </div>
        </div>
      </div>

      <div class="chart-section">
        <div class="section-header">
          <h2>商机阶段分布</h2>
        </div>
        <Spin :spinning="stageLoading">
          <div ref="stageChartRef" class="stage-chart"></div>
          <Empty v-if="!hasStageData" description="暂无阶段分布数据" />
        </Spin>
      </div>
    </template>

    <!-- Todo 区域 -->
    <div class="todo-section">
      <div class="section-header">
        <h2>我的待办</h2>
      </div>
      <div class="todo-grid">
        <!-- 待我审批 -->
        <Card size="small" title="待我审批" :bordered="true" class="todo-card">
          <template #extra>
            <a class="todo-link" @click="go('/sale/stage-approval')">全部</a>
          </template>
          <Spin :spinning="approvalLoading">
            <ul class="todo-list">
              <li v-for="item in pendingApprovals" :key="item.id" class="todo-item" @click="goApproval(item)">
                <span class="todo-title">{{ item.opportunityName || '未命名商机' }}</span>
                <span class="todo-meta">{{ item.targetStage || '—' }}</span>
              </li>
              <li v-if="!pendingApprovals.length" class="todo-empty">暂无</li>
            </ul>
          </Spin>
        </Card>

        <!-- 我的未完成任务 -->
        <Card size="small" title="我的未完成任务" :bordered="true" class="todo-card">
          <template #extra>
            <a class="todo-link" @click="go('/contact/task')">全部</a>
          </template>
          <Spin :spinning="taskLoading">
            <ul class="todo-list">
              <li v-for="task in pendingTasks" :key="task.id" class="todo-item" @click="goTask(task)">
                <span class="todo-title">{{ task.taskTitle || '未命名任务' }}</span>
                <span class="todo-meta">{{ task.deadlineText }}</span>
              </li>
              <li v-if="!pendingTasks.length" class="todo-empty">暂无</li>
            </ul>
          </Spin>
        </Card>

        <!-- 联系人生日 -->
        <Card size="small" title="联系人生日" :bordered="true" class="todo-card">
          <template #extra>
            <a class="todo-link" @click="go('/client/contacts')">全部</a>
          </template>
          <Spin :spinning="birthdayLoading">
            <ul class="todo-list">
              <li v-for="item in birthdays" :key="item.id ?? item.contactId" class="todo-item" @click="goBirthday(item)">
                <span class="todo-title">{{ item.name }}</span>
                <span class="todo-meta">{{ birthdayText(item) }}</span>
              </li>
              <li v-if="!birthdays.length" class="todo-empty">暂无</li>
            </ul>
          </Spin>
        </Card>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { useRouter } from 'vue-router';
import { Card, Empty, Spin } from 'ant-design-vue';
import dayjs from 'dayjs';
import { axiosInstance } from '@/api/apiClient';
import { useQueryMyPermissions } from '@/hooks/usePremission';
import { useQueryCurrentUser } from '@/hooks/useUser';
import { extractPermissionNames } from '@/types/permission';
import { useEChart } from '@/hooks/useEChart';
import type { EChartsCoreOption } from 'echarts/core';

definePage({ name: 'home' });

const router = useRouter();

const { data: myPermissions } = useQueryMyPermissions();
const { data: currentUser } = useQueryCurrentUser();

/** 权限 501（报表查看）→ 是否展示统计卡片区 */
const showStats = computed(() => {
  const names = extractPermissionNames(myPermissions.value);
  return names.includes('report:REPORT_VIEW_REPORT');
});

// ---------- 统计概览 ----------
interface StatisticsSummary {
  totalOpportunities?: number;
  newOpportunities?: number;
  signedContracts?: number;
  totalContractAmount?: number;
  totalPaymentAmount?: number;
  paymentCount?: number;
}
const summaryData = ref<StatisticsSummary | null>(null);

const statisticCards = computed(() => {
  const s = summaryData.value ?? {};
  return [
    { key: 'totalOpportunities', label: '商机总数', value: fmt(s.totalOpportunities) },
    { key: 'newOpportunities', label: '本月新增商机', value: fmt(s.newOpportunities) },
    { key: 'signedContracts', label: '签约合同数', value: fmt(s.signedContracts) },
    { key: 'totalContractAmount', label: '签约总额', value: money(s.totalContractAmount) },
    { key: 'totalPaymentAmount', label: '回款总额', value: money(s.totalPaymentAmount) },
    { key: 'paymentCount', label: '回款笔数', value: fmt(s.paymentCount) },
  ];
});

function fmt(v: number | undefined): string {
  return typeof v === 'number' ? String(v) : '0';
}
function money(v: number | undefined): string {
  if (typeof v !== 'number') return '0';
  return Number(v.toFixed(2)).toLocaleString('zh-CN');
}

async function loadSummary() {
  try {
    const start = dayjs().startOf('month').format('YYYY-MM-DD HH:mm:ss');
    const end = dayjs().format('YYYY-MM-DD HH:mm:ss');
    const res = await axiosInstance.post('/dataStatistics/summary', {
      startTime: start,
      endTime: end,
    });
    summaryData.value = (res.data?.data ?? {}) as StatisticsSummary;
  } catch {
    // 无权限/失败时统计区不展示数据，不阻断页面
    summaryData.value = null;
  }
}

// ---------- 商机阶段分布图 ----------
interface StageDistribution {
  stage0Count?: number;
  stage0Percentage?: number;
  stage1Count?: number;
  stage1Percentage?: number;
  stage2Count?: number;
  stage2Percentage?: number;
  stage3Count?: number;
  stage3Percentage?: number;
  stage4Count?: number;
  stage4Percentage?: number;
  stage5Count?: number;
  stage5Percentage?: number;
  totalCount?: number;
}
const stageData = ref<StageDistribution | null>(null);
const stageLoading = ref(false);
const stageChartRef = ref<HTMLDivElement>();
const STAGE_LABELS = ['种子', '潜在', '确认', '储备', '立项签约', '关闭'];

const stageEntries = computed(() => {
  const s = stageData.value;
  if (!s) return [];
  return STAGE_LABELS.map((label, i) => {
    const count = (s as unknown as Record<string, unknown>)[`stage${i}Count`];
    return {
      name: label,
      value: typeof count === 'number' ? count : 0,
    };
  });
});
const hasStageData = computed(() => stageEntries.value.some((e) => e.value > 0));

useEChart(stageChartRef, {
  getOption: () => {
    if (!stageEntries.value.length) return {};
    const option: EChartsCoreOption = {
      tooltip: { trigger: 'item' },
      legend: { bottom: 0 },
      series: [
        {
          type: 'pie',
          radius: ['40%', '70%'],
          center: ['50%', '48%'],
          data: stageEntries.value,
          label: { formatter: '{b}: {c}' },
        },
      ],
    };
    return option;
  },
});

async function loadStageDistribution() {
  stageLoading.value = true;
  try {
    const res = await axiosInstance.get('/dataStatistics/opportunityStageDistribution');
    stageData.value = (res.data?.data ?? null) as StageDistribution | null;
  } catch {
    stageData.value = null;
  } finally {
    stageLoading.value = false;
  }
}

// ---------- 待办：待我审批 ----------
interface ApprovalItem {
  id?: number;
  opportunityId?: number;
  opportunityName?: string;
  targetStage?: string;
  approverId?: number;
  approvalStatus?: number;
}
const pendingApprovals = ref<ApprovalItem[]>([]);
const approvalLoading = ref(false);

async function loadApprovals() {
  const currentId = currentUser.value?.id;
  if (currentId === undefined) return;
  approvalLoading.value = true;
  try {
    const res = await axiosInstance.get('/sales/stage', {
      params: {
        pageNum: 1,
        pageSize: 20,
        'dto.approvalStatus': [0],
        'dto.approverId': currentId,
      },
    });
    pendingApprovals.value = (res.data?.data?.records ?? []) as ApprovalItem[];
  } catch {
    pendingApprovals.value = [];
  } finally {
    approvalLoading.value = false;
  }
}

// ---------- 待办：我的未完成任务（0 未开始 / 1 进行中） ----------
interface PendingTask {
  id?: number;
  taskTitle?: string;
  status?: string;
  endTime?: string;
  deadlineText?: string;
}
const pendingTasks = ref<PendingTask[]>([]);
const taskLoading = ref(false);

async function loadTasks() {
  const currentId = currentUser.value?.id;
  if (currentId === undefined) return;
  taskLoading.value = true;
  try {
    // 未完成 = 未开始(0) + 进行中(1)，接口单值过滤，故并行取两个状态
    const [r0, r1] = await Promise.all([
      axiosInstance.post('/contactTask/query', {
        assigneeId: currentId,
        status: 0,
      }, { params: { pageNum: 1, pageSize: 100 } }),
      axiosInstance.post('/contactTask/query', {
        assigneeId: currentId,
        status: 1,
      }, { params: { pageNum: 1, pageSize: 100 } }),
    ]);
    const reduce = (recs: PendingTask[], r: unknown) => {
      const rr = Array.isArray((r as { data?: { data?: { records?: PendingTask[] } } })?.data?.data?.records)
        ? (r as { data: { data: { records: PendingTask[] } } }).data.data.records
        : [];
      const withDeadline = rr.map((t) => ({
        ...t,
        deadlineText: t.endTime ? String(t.endTime).slice(0, 16) : undefined,
      }));
      return recs.concat(withDeadline);
    };
    pendingTasks.value = reduce([], r0).concat(reduce([], r1));
  } catch {
    pendingTasks.value = [];
  } finally {
    taskLoading.value = false;
  }
}

// ---------- 待办：联系人生日 ----------
interface BirthdayItem {
  contactId?: number;
  id?: number;
  name?: string;
  birthday?: string;
  daysUntil?: number;
  relation?: string;
}
const birthdays = ref<BirthdayItem[]>([]);
const birthdayLoading = ref(false);

async function loadBirthdays() {
  birthdayLoading.value = true;
  try {
    const res = await axiosInstance.get('/contact/birthdays');
    birthdays.value = Array.isArray(res.data?.data) ? (res.data.data as BirthdayItem[]) : [];
  } catch {
    birthdays.value = [];
  } finally {
    birthdayLoading.value = false;
  }
}

function birthdayText(item: BirthdayItem): string {
  if (typeof item.daysUntil === 'number') {
    return item.daysUntil === 0 ? '今天' : `${item.daysUntil} 天后`;
  }
  return item.birthday ?? '';
}

// ---------- 导航 ----------
function go(path: string) {
  void router.push(path);
}
function goApproval(item: ApprovalItem) {
  if (item.opportunityId) {
    void router.push(`/sale/detail/${item.opportunityId}`);
    return;
  }
  void router.push('/sale/stage-approval');
}
function goTask(task: PendingTask) {
  if (task.id) {
    void router.push({ path: '/contact/task', query: { id: String(task.id) } });
    return;
  }
  void router.push('/contact/task');
}
function goBirthday(item: BirthdayItem) {
  const id = item.id ?? item.contactId;
  if (id) {
    void router.push({ path: '/client/contacts', query: { id: String(id) } });
    return;
  }
  void router.push('/client/contacts');
}

onMounted(() => {
  if (showStats.value) {
    void loadSummary();
    void loadStageDistribution();
  }
  void loadApprovals();
  void loadTasks();
  void loadBirthdays();
});
</script>

<style scoped>
.home-dashboard {
  padding: 16px;
  display: flex;
  flex-direction: column;
  gap: 20px;
}
.section-header {
  display: flex;
  align-items: baseline;
  gap: 12px;
  margin-bottom: 12px;
}
.section-header h2 {
  font-size: 18px;
  margin: 0;
  font-weight: 600;
}
.section-sub {
  font-size: 12px;
  color: #999;
}
.stat-card-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(160px, 1fr));
  gap: 12px;
}
.stat-card {
  background: #fff;
  border: 1px solid #f0f0f0;
  border-radius: 8px;
  padding: 16px;
}
.stat-label {
  font-size: 13px;
  color: #888;
  margin-bottom: 8px;
}
.stat-value {
  font-size: 22px;
  font-weight: 600;
  color: #1677ff;
  word-break: break-all;
}
.chart-section {
  background: #fff;
  border: 1px solid #f0f0f0;
  border-radius: 8px;
  padding: 16px;
}
.stage-chart {
  height: 320px;
  width: 100%;
}
.todo-section {
  background: #fff;
  border: 1px solid #f0f0f0;
  border-radius: 8px;
  padding: 16px;
}
.todo-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(280px, 1fr));
  gap: 12px;
}
.todo-card {
  border-radius: 8px;
}
.todo-link {
  font-size: 12px;
}
.todo-list {
  list-style: none;
  margin: 0;
  padding: 0;
}
.todo-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 8px 0;
  border-bottom: 1px dashed #f0f0f0;
  cursor: pointer;
}
.todo-item:hover {
  background: #fafafa;
}
.todo-item:last-child {
  border-bottom: none;
}
.todo-title {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  flex: 1;
  font-size: 13px;
}
.todo-meta {
  color: #999;
  font-size: 12px;
  white-space: nowrap;
}
.todo-empty {
  color: #bbb;
  padding: 8px 0;
}
</style>