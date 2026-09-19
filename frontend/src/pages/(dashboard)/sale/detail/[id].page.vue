<template>
  <div class="w-full md:p-4">
    <div class="flex justify-between items-center mb-4">
      <div class="flex items-center gap-2">
        <a-button @click="handleBack">{{ assistId ? '返回协助' : '返回' }}</a-button>
        <h2 class="text-xl font-semibold text-[#1890ff]">销售订单详情</h2>
      </div>
      <a-button :loading="isRefetching" @click="handleRefetch">刷新</a-button>
    </div>

    <!-- 加载状态 -->
    <div v-if="isLoading" class="text-center py-[50px] text-[#999]">
      <a-spin tip="加载销售订单详情中..." />
    </div>

    <!-- 错误状态 -->
    <div v-else-if="isError" class="text-center py-[50px] text-[#ff4d4f]">
      <p class="mb-4">加载失败: {{ error?.message || '未知错误' }}</p>
      <a-button @click="handleRefetch">重新加载</a-button>
    </div>

    <!-- 数据展示 -->
    <template v-else-if="saleDetail">
      <!-- 阶段流程 -->
      <a-card class="mb-4" data-tour="detail-stageflow">
        <div>
          <StageFlow :current-stage="saleDetail.stage ?? 0" />
        </div>
      </a-card>

      <!-- 基本信息 -->
      <a-card title="基本信息" class="mb-4">
        <Descriptions :column="isMobile ? 1 : 2" bordered>
          <Descriptions.Item label="销售订单名称">
            {{ saleDetail.opportunityName || '-' }}
          </Descriptions.Item>
          <Descriptions.Item label="销售订单ID">
            {{ saleDetail.id || '-' }}
          </Descriptions.Item>
          <Descriptions.Item label="公司名称">
            {{ saleDetail.companyName || '-' }}
          </Descriptions.Item>
          <Descriptions.Item label="联系人">
            {{ saleDetail.contactName || '-' }}
          </Descriptions.Item>
          <Descriptions.Item label="预计金额">
            ¥{{ formatAmount(saleDetail.amount ?? 0) }}
          </Descriptions.Item>
          <Descriptions.Item label="销售阶段">
            <a-tag color="blue">
              {{ getStageText(saleDetail.stage ?? 0) }}
            </a-tag>
          </Descriptions.Item>
          <Descriptions.Item label="预计成交日期">
            {{ saleDetail.expectedCloseDate || '-' }}
          </Descriptions.Item>
          <Descriptions.Item label="销售来源">
            {{ saleDetail.source || '-' }}
          </Descriptions.Item>
          <Descriptions.Item label="负责人">
            {{ saleDetail.ownerName || '-' }}
          </Descriptions.Item>
          <Descriptions.Item label="创建人">
            {{ saleDetail.creatorName || '-' }}
          </Descriptions.Item>
          <Descriptions.Item label="审批人">
            {{ saleDetail.approverName || '-' }}
          </Descriptions.Item>
          <Descriptions.Item label="创建时间">
            {{ saleDetail.createTime || '-' }}
          </Descriptions.Item>
          <Descriptions.Item label="更新时间">
            {{ saleDetail.updateTime || '-' }}
          </Descriptions.Item>
          <Descriptions.Item label="描述" >
            {{ saleDetail.description || '无描述' }}
          </Descriptions.Item>
        </Descriptions>
      </a-card>

      <!-- 业务活动列表 -->
      <a-card title="业务活动" class="mb-4" data-tour="detail-activities">
        <template v-if="activitiesByStageList.length > 0">
          <div v-for="group in activitiesByStageList" :key="group.stage" class="mb-4">
            <h3 class="text-lg font-semibold mb-2">{{ group.stage }}</h3>
            <a-table
              :scroll="{ x: 'max-content' }"
              :columns="activityColumns"
              :data-source="group.activities"
              :pagination="false"
              size="small"
              :row-key="(record: ActivityRecord) => record.id ?? ''"
            >
              <template #bodyCell="{ column, record }">
                <template v-if="column.key === 'activityTime'">
                  {{ record.activityTime || '-' }}
                </template>
                <template v-if="column.key === 'activityType'">
                  <a-tag color="blue">{{ record.activityType || '-' }}</a-tag>
                </template>
                <template v-if="column.key === 'activityDuration'">
                  {{ record.activityDuration ? `${record.activityDuration}分钟` : '-' }}
                </template>
                <template v-if="column.key === 'viewActivity'">
                  <a
                    class="text-blue-500 hover:text-blue-700"
                    @click="goActivityDetail(record)"
                  >
                    详情
                  </a>
                </template>
              </template>
            </a-table>
          </div>
        </template>
        <a-empty v-else description="暂无业务活动" />
      </a-card>

      <!-- 项目文件 -->
      <a-card v-if="!assistId" class="mb-4" data-tour="detail-files">
      <ProjectFileList :opportunity-id="saleId" />
      </a-card>

      <!-- 商机状态变更记录 -->
      <a-card title="商机状态变更记录" class="mb-4" data-tour="detail-records">
        <template v-if="stageChangeRecords && stageChangeRecords.length > 0">
          <a-table
            :scroll="{ x: 'max-content' }"
            :columns="stageChangeColumns"
            :data-source="stageChangeRecords"
            :pagination="false"
            size="small"
            :row-key="(record: StageChangeRecord) => record.id ?? ''"
          >
            <template #bodyCell="{ column, record }">
              <template v-if="column.key === 'currentStage'">
                <a-tag>{{ record.currentStage || '-' }}</a-tag>
              </template>
              <template v-if="column.key === 'targetStage'">
                <a-tag color="green">{{ record.targetStage || '-' }}</a-tag>
              </template>
              <template v-if="column.key === 'approvalStatus'">
                <a-tag :color="getApprovalStatusColor(record.approvalStatus)">
                  {{ record.approvalStatusDesc || '-' }}
                </a-tag>
              </template>
              <template v-if="column.key === 'approvalOpinion'">
                {{ record.approvalOpinion || record.message || '-' }}
              </template>
              <template v-if="column.key === 'assistUsers'">
                <AssistUserTags
                  :users="record.assistUsers"
                  model-name="sales_stage_approval"
                  :record-id="record.id"
                />
              </template>
              <template v-if="column.key === 'viewApproval'">
                <a
                  class="text-blue-500 hover:text-blue-700"
                  @click="goApprovalDetail(record)"
                >
                  详情
                </a>
              </template>
            </template>
          </a-table>
        </template>
        <a-empty v-else description="暂无状态变更记录" />
      </a-card>
    </template>

    <!-- 空状态 -->
    <a-empty v-else description="未找到销售订单信息" />

    <ViewActivityModal ref="activityDetailModalRef" />
  </div>
</template>

<script lang="ts" setup>
import { computed, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { Card, Descriptions, Grid, Button, Spin, Empty, Table, Tag } from 'ant-design-vue';
import { useSaleOpportunityDetail } from '@/hooks/useSale';
import { useQueryAssistOpportunity } from '@/hooks/useAssist';
import { stageMap } from '@/constants/sale/constant';

const screens = Grid.useBreakpoint();
const isMobile = computed(() => !screens.value.md);
import type { GetSalesDetailByOpportunityIdResponse } from '@/api/axios';
import ProjectFileList from '@/components/sale/ProjectFileList.vue';
import StageFlow from '@/components/sale/StageFlow.vue';
import ViewActivityModal from '@/components/activity/ViewActivityModal.vue';
import AssistUserTags from '@/components/assist/AssistUserTags.vue';

type SaleDetail = NonNullable<GetSalesDetailByOpportunityIdResponse['data']>;
type ActivityRecord = NonNullable<
  NonNullable<SaleDetail['activitiesByStage']>['key']
>[number];
type StageChangeRecord = NonNullable<SaleDetail['stageChangeRecords']>[number];
type ActivityGroup = {
  stage: string;
  activities: ActivityRecord[];
};

definePage({
  name: 'detail',
});

const ACard = Card;
const AButton = Button;
const ASpin = Spin;
const AEmpty = Empty;
const ATable = Table;
const ATag = Tag;
const route = useRoute();
const router = useRouter();

// 获取销售订单ID
const saleId = computed(() => {
  const id = (route.params as Record<string, string | string[] | undefined>).id;
  return typeof id === 'string' ? Number(id) : undefined;
});

// 协助入口携带 assistId 时，详情请求必须走协助范围接口，不能回退到普通商机列表权限链路。
const assistId = computed(() => {
  const value = route.query.assistId;
  const parsed = Array.isArray(value) ? value[0] : value;
  const id = parsed == null ? Number.NaN : Number(parsed);
  return Number.isFinite(id) && id > 0 ? id : undefined;
});
const normalSaleId = computed(() => (assistId.value ? undefined : saleId.value));

// 使用新Hook获取销售订单详情
const {
  data: normalSaleDetail,
  isLoading: normalLoading,
  isError: normalError,
  error: normalQueryError,
  refetch: refetchNormal,
  isRefetching: normalRefetching,
} = useSaleOpportunityDetail(normalSaleId);
const {
  data: assistSaleDetail,
  isLoading: assistLoading,
  isError: assistError,
  error: assistQueryError,
  refetch: refetchAssist,
  isRefetching: assistRefetching,
} = useQueryAssistOpportunity(assistId);

const saleDetail = computed(() => assistId.value ? assistSaleDetail.value : normalSaleDetail.value);
const isLoading = computed(() => assistId.value ? assistLoading.value : normalLoading.value);
const isError = computed(() => assistId.value ? assistError.value : normalError.value);
const error = computed(() => assistId.value ? assistQueryError.value : normalQueryError.value);
const isRefetching = computed(() => assistId.value ? assistRefetching.value : normalRefetching.value);

// 业务活动表格列
const activityColumns = [
  { title: '活动标题', dataIndex: 'activityTitle', key: 'activityTitle' },
  { title: '活动内容', dataIndex: 'activityContent', key: 'activityContent' },
  { title: '活动时间', dataIndex: 'activityTime', key: 'activityTime' },
  { title: '活动类型', dataIndex: 'activityType', key: 'activityType' },
  { title: '时长(分钟)', dataIndex: 'activityDuration', key: 'activityDuration' },
  { title: '备注', dataIndex: 'remark', key: 'remark' },
  { title: '操作', key: 'viewActivity' },
];

// 状态变更记录表格列
const stageChangeColumns = [
  { title: '旧阶段', dataIndex: 'currentStage', key: 'currentStage' },
  { title: '目标阶段', dataIndex: 'targetStage', key: 'targetStage' },
  { title: '审批状态', dataIndex: 'approvalStatus', key: 'approvalStatus' },
  { title: '审批意见', dataIndex: 'approvalOpinion', key: 'approvalOpinion' },
  { title: '协助人', dataIndex: 'assistUsers', key: 'assistUsers' },
  { title: '申请时间', dataIndex: 'applyTime', key: 'applyTime' },
  { title: '操作', key: 'viewApproval' },
];

// 将 activitiesByStage 转换为数组格式以便渲染
const activitiesByStageList = computed(() => {
  const data = saleDetail.value?.activitiesByStage as
    | Record<string, ActivityRecord[]>
    | undefined;
  if (!data) return [] as ActivityGroup[];

  return Object.entries(data)
    .filter(([, activities]) => Array.isArray(activities))
    .map(([stage, activities]) => ({
      stage,
      activities,
    }));
});

// 状态变更记录
const stageChangeRecords = computed<StageChangeRecord[]>(
  () => saleDetail.value?.stageChangeRecords ?? [],
);

// 格式化函数
const formatAmount = (amount: number) => {
  if (!amount && amount !== 0) return '0.00';
  return new Intl.NumberFormat('zh-CN', {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  }).format(amount);
};

const getStageText = (stage: number) => {
  return stageMap[stage] || `未知阶段(${stage})`;
};

const getApprovalStatusColor = (status?: number) => {
  const colorMap: Record<number, string> = {
    0: 'orange', // 待审批
    1: 'green', // 同意
    2: 'red', // 拒绝
    3: 'gray', // 退回修改
  };
  return status !== undefined ? colorMap[status] : 'default';
};

const handleRefetch = () => {
  void (assistId.value ? refetchAssist() : refetchNormal());
};

const handleBack = () => {
  if (assistId.value) {
    void router.push('/assist');
    return;
  }
  router.back();
};

const activityDetailModalRef = ref<InstanceType<typeof ViewActivityModal>>();

const goActivityDetail = (record: ActivityRecord) => {
  activityDetailModalRef.value?.open(record);
};

const goApprovalDetail = (record: StageChangeRecord) => {
  if (record.id) {
    void router.push(`/sale/stage-approval?approvalId=${record.id}`);
  }
};
</script>
