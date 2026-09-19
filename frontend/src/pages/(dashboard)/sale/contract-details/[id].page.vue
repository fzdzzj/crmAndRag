<template>
  <div class="w-full md:p-4">
    <div class="flex justify-between items-center mb-4">
      <div class="flex items-center gap-2">
        <a-button @click="handleBack">返回</a-button>
        <h2 class="text-xl font-semibold text-[#1890ff]">合同详情</h2>
      </div>
      <a-space>
        <a-button type="primary" @click="handleAddPayment">添加回款</a-button>
        <a-button type="primary" @click="handleAddInvoice">添加开票</a-button>
        <a-button :loading="isRefetching" @click="handleRefetch">刷新</a-button>
      </a-space>
    </div>

    <!-- 加载状态 -->
    <div v-if="isLoading" class="text-center py-[50px] text-[#999]">
      <a-spin tip="加载合同详情中..." />
    </div>

    <!-- 错误状态 -->
    <div v-else-if="isError" class="text-center py-[50px] text-[#ff4d4f]">
      <p class="mb-4">加载失败: {{ error?.message || '未知错误' }}</p>
      <a-button @click="handleRefetch">重新加载</a-button>
    </div>

    <!-- 数据展示 -->
    <template v-else-if="contractDetail">
      <!-- 合同基本信息 -->
      <a-card title="合同基本信息" class="mb-4">
        <Descriptions :column="isMobile ? 1 : 2" bordered>
          <Descriptions.Item label="合同编号">
            {{ contractDetail.contract?.contractNo || '-' }}
          </Descriptions.Item>
          <Descriptions.Item label="关联合同">
            {{ contractDetail.contract?.opportunityName || '-' }}
          </Descriptions.Item>
          <Descriptions.Item label="合同名称">
            {{ contractDetail.contract?.contractName || '-' }}
          </Descriptions.Item>
          <Descriptions.Item label="公司名称">
            {{ contractDetail.contract?.companyName || '-' }}
          </Descriptions.Item>
          <Descriptions.Item label="负责人">
            {{ contractDetail.contract?.ownerName || '-' }}
          </Descriptions.Item>
          <Descriptions.Item label="创建人">
            {{ contractDetail.contract?.creatorName || '-' }}
          </Descriptions.Item>
          <Descriptions.Item label="合同金额">
            ¥{{ formatAmount(contractDetail.contract?.totalAmount ?? 0) }} 人民币
          </Descriptions.Item>
          <Descriptions.Item label="签约日期">
            {{ contractDetail.contract?.signDate || '-' }}
          </Descriptions.Item>
          <Descriptions.Item label="生效时间">
            {{ contractDetail.contract?.startDate || '-' }}
          </Descriptions.Item>
          <Descriptions.Item label="合同完成时间">
            {{ contractDetail.contract?.endDate || '-' }}
          </Descriptions.Item>
          <Descriptions.Item label="合同状态">
            {{ getStatusText(contractDetail.contract?.contractStatus ?? 0) }}
          </Descriptions.Item>
          <Descriptions.Item label="创建时间">
            {{ contractDetail.contract?.createTime || '-' }}
          </Descriptions.Item>
          <Descriptions.Item label="合同摘要" :span="2">
            {{ contractDetail.contract?.contractName || '无摘要信息' }}
          </Descriptions.Item>
        </Descriptions>
      </a-card>

      <!-- 订单信息 -->
      <a-card
        v-for="(order, index) in contractDetail.orders"
        :key="order.id"
        :title="`订单信息 ${index + 1}`"
        class="mb-4"
      >
        <Descriptions :column="isMobile ? 1 : 2" bordered>
          <Descriptions.Item label="订单ID">
            {{ order.id || '-' }}
          </Descriptions.Item>
          <Descriptions.Item label="合同编号">
            {{ contractDetail.contract?.contractNo || '-' }}
          </Descriptions.Item>
          <Descriptions.Item label="产品名称">
            {{ order.productName || '-' }}
          </Descriptions.Item>
          <Descriptions.Item label="数量">
            {{ formatAmount(order.quantity ?? 0) }}
          </Descriptions.Item>
          <Descriptions.Item label="单价">
            ¥{{ formatAmount(order.unitPrice ?? 0) }} 人民币
          </Descriptions.Item>
          <Descriptions.Item label="金额">
            ¥{{ formatAmount(order.amount ?? 0) }} 人民币
          </Descriptions.Item>
          <Descriptions.Item label="备注" :span="2">
            {{ order.remark || '无备注' }}
          </Descriptions.Item>
        </Descriptions>
      </a-card>

      <a-card title="关联商机详情" class="mb-4">
        <template v-if="relatedOpportunity">
          <Descriptions :column="isMobile ? 1 : 2" bordered>
            <Descriptions.Item label="商机名称">
              {{ relatedOpportunity.opportunityName || '-' }}
            </Descriptions.Item>
            <Descriptions.Item label="公司名称">
              {{ relatedOpportunity.companyName || '-' }}
            </Descriptions.Item>
            <Descriptions.Item label="联系人">
              {{ relatedOpportunity.contactName || '-' }}
            </Descriptions.Item>
            <Descriptions.Item label="当前阶段">
              <a-tag color="blue">
                {{
                  getOpportunityStageText(relatedOpportunity.stage)
                }}
              </a-tag>
            </Descriptions.Item>
            <Descriptions.Item label="预计金额">
              ¥{{ formatAmount(relatedOpportunity.amount ?? 0) }}
            </Descriptions.Item>
            <Descriptions.Item label="预计成交日期">
              {{ relatedOpportunity.expectedCloseDate || '-' }}
            </Descriptions.Item>
            <Descriptions.Item label="负责人">
              {{ relatedOpportunity.ownerName || '-' }}
            </Descriptions.Item>
            <Descriptions.Item label="审批人">
              {{ relatedOpportunity.approverName || '-' }}
            </Descriptions.Item>
            <Descriptions.Item label="创建时间">
              {{ relatedOpportunity.createTime || '-' }}
            </Descriptions.Item>
            <Descriptions.Item label="描述" :span="2">
              {{ relatedOpportunity.description || '无商机描述' }}
            </Descriptions.Item>
            <Descriptions.Item label="查看完整详情" :span="2">
              <a-button
                type="link"
                class="px-0"
                @click="handleViewOpportunityDetail"
              >
                打开商机详情页
              </a-button>
            </Descriptions.Item>
          </Descriptions>

          <div class="mt-4">
            <div class="mb-2 text-base font-medium">关联业务活动</div>
            <template v-if="relatedActivitiesByStageList.length > 0">
              <div
                v-for="group in relatedActivitiesByStageList"
                :key="group.stage"
                class="mb-4 last:mb-0"
              >
                <div class="mb-2 text-sm font-medium text-slate-600">
                  {{ group.stage }}
                </div>
                <a-table
                  :scroll="{ x: 'max-content' }"
                  :columns="opportunityActivityColumns"
                  :data-source="group.activities"
                  :pagination="false"
                  size="small"
                  :row-key="(record: ActivityRecord) => record.id ?? ''"
                >
                  <template #bodyCell="{ column, record }">
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
            <a-empty v-else description="暂无关联业务活动" />
          </div>

          <div class="mt-4">
            <div class="mb-2 text-base font-medium">阶段审批轨迹</div>
            <a-table
              v-if="relatedStageChangeRecords.length > 0"
              :scroll="{ x: 'max-content' }"
              :columns="opportunityStageColumns"
              :data-source="relatedStageChangeRecords"
              :pagination="false"
              size="small"
              :row-key="(record: StageChangeRecord) => record.id ?? ''"
            >
              <template #bodyCell="{ column, record }">
                <template v-if="column.key === 'approvalStatus'">
                  <a-tag
                    :color="
                      record.approvalStatus === 1
                        ? 'green'
                        : record.approvalStatus === 2
                          ? 'red'
                          : 'orange'
                    "
                  >
                    {{ record.approvalStatusDesc || '-' }}
                  </a-tag>
                </template>
                <template v-if="column.key === 'approvalOpinion'">
                  {{ record.approvalOpinion || record.message || '-' }}
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
            <a-empty v-else description="暂无阶段审批记录" />
          </div>
        </template>
        <div v-else-if="isOpportunityLoading" class="py-10 text-center">
          <a-spin tip="加载关联商机中..." />
        </div>
        <a-empty v-else description="暂无关联商机信息" />
      </a-card>

      <!-- 项目文件 -->
      <a-card class="mb-4">
        <ProjectFileList :contract-id="contractId" />
      </a-card>

      <!-- 开票信息列表 -->
      <a-card title="开票信息" class="mb-4">
        <a-table
          :scroll="{ x: 'max-content' }"
          :columns="invoiceColumns"
          :data-source="invoiceList || []"
          :loading="isInvoicesLoading"
          :pagination="false"
          row-key="id"
          size="small"
        />
      </a-card>

      <!-- 回款记录列表 -->
      <a-card title="回款记录" class="mb-4">
        <a-table
          :scroll="{ x: 'max-content' }"
          :columns="paymentColumns"
          :data-source="paymentList || []"
          :loading="isPaymentsLoading"
          :pagination="false"
          row-key="id"
          size="small"
        />
      </a-card>
    </template>

    <!-- 空状态 -->
    <a-empty v-else description="未找到合同信息" />

    <!-- 开票信息模态框 -->
    <CreateInvoiceModal
      v-model:open="showInvoiceModal"
      :contract-id="contractId"
      :contract-no="contractDetail?.contract?.contractNo"
      :contract-name="contractDetail?.contract?.contractName"
      @success="handleInvoiceSuccess"
    />

    <!-- 回款记录模态框 -->
    <CreatePaymentModal
      v-model:open="showPaymentModal"
      :contract-id="contractId"
      :contract-no="contractDetail?.contract?.contractNo"
      :contract-name="contractDetail?.contract?.contractName"
      @success="handlePaymentSuccess"
    />

    <ViewActivityModal ref="activityDetailModalRef" />
  </div>
</template>

<script lang="ts" setup>
import { computed, ref, h } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import {
  Card,
  Descriptions,
  Button,
  Space,
  Table,
  Tag,
  Spin,
  Empty,
  Grid,
} from 'ant-design-vue';
import { useContractDetail } from '@/hooks/useContract';
import { useInvoiceByContract, usePaymentByContract } from '@/hooks/useFinance';
import { useSaleOpportunityDetailByContract } from '@/hooks/useSale';
import CreateInvoiceModal from '@/components/finance/CreateInvoiceModal.vue';
import CreatePaymentModal from '@/components/finance/CreatePaymentModal.vue';
import ProjectFileList from '@/components/sale/ProjectFileList.vue';
import ViewActivityModal from '@/components/activity/ViewActivityModal.vue';
import { stageMap } from '@/constants/sale/constant';

const screens = Grid.useBreakpoint();
const isMobile = computed(() => !screens.value.md);
import type {
  GetInvoiceInfoContractByContractIdResponse,
  GetPaymentRecordContractByContractIdResponse,
  GetSalesDetailByContractByContractIdResponse,
} from '@/api/axios';

type OpportunityDetail = NonNullable<GetSalesDetailByContractByContractIdResponse['data']>;
type InvoiceRecord = NonNullable<GetInvoiceInfoContractByContractIdResponse['data']>[number];
type PaymentRecord = NonNullable<GetPaymentRecordContractByContractIdResponse['data']>[number];
type ActivityRecord = NonNullable<
  NonNullable<OpportunityDetail['activitiesByStage']>['key']
>[number];
type StageChangeRecord = NonNullable<OpportunityDetail['stageChangeRecords']>[number];
type ActivityGroup = {
  stage: string;
  activities: ActivityRecord[];
};

definePage({
  name: 'ContractDetails',
});

const ACard = Card;
const AButton = Button;
const ASpace = Space;
const ATable = Table;
const ASpin = Spin;
const AEmpty = Empty;
const route = useRoute();

// 获取合同ID
const contractId = computed(() => {
  const id = (route.params as Record<string, string | string[] | undefined>).id;
  return typeof id === 'string' ? Number(id) : undefined;
});

// 使用Hook获取合同详情
const {
  data: contractDetail,
  isLoading,
  isError,
  error,
  refetch,
  isRefetching,
} = useContractDetail(contractId);

// 获取开票信息列表
const { data: invoiceList, isLoading: isInvoicesLoading } =
  useInvoiceByContract(contractId);

// 获取回款记录列表
const { data: paymentList, isLoading: isPaymentsLoading } =
  usePaymentByContract(contractId);

// 获取合同关联商机详情
const {
  data: relatedOpportunity,
  isLoading: isOpportunityLoading,
} = useSaleOpportunityDetailByContract(contractId);

const handleRefetch = () => {
  void refetch();
};

// 格式化函数
const formatAmount = (amount: number) => {
  if (!amount && amount !== 0) return '0.00';
  return new Intl.NumberFormat('zh-CN', {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  }).format(amount);
};

const getStatusText = (status: number) => {
  const statusMap = {
    0: '预签约',
    1: '已生效',
    2: '已终止',
    3: '已完成',
    4: '已弃用',
  };
  return statusMap[status as keyof typeof statusMap] || `未知状态(${status})`;
};

const getOpportunityStageText = (stage?: number) => {
  if (stage === undefined) {
    return '-';
  }

  return stageMap[stage] || `未知阶段(${stage})`;
};

// 开票状态
const getInvoiceStatusTag = (status?: number) => {
  const statusMap = {
    0: { text: '已开具', color: 'success' },
    1: { text: '已作废', color: 'error' },
  };
  const statusInfo = statusMap[status as keyof typeof statusMap];
  return statusInfo
    ? h(Tag, { color: statusInfo.color }, () => statusInfo.text)
    : '-';
};

// 回款状态
const getPaymentStatusTag = (status?: number) => {
  const statusMap = {
    0: { text: '已确认', color: 'success' },
    1: { text: '待确认', color: 'warning' },
  };
  const statusInfo = statusMap[status as keyof typeof statusMap];
  return statusInfo
    ? h(Tag, { color: statusInfo.color }, () => statusInfo.text)
    : '-';
};

// 开票信息表格列
const invoiceColumns = [
  { title: '发票编号', dataIndex: 'invoiceNo', key: 'invoiceNo' },
  { title: '开票金额', dataIndex: 'invoiceAmount', key: 'invoiceAmount' },
  { title: '开票日期', dataIndex: 'invoiceDate', key: 'invoiceDate' },
  { title: '发票类型', dataIndex: 'invoiceType', key: 'invoiceType' },
  {
    title: '状态',
    dataIndex: 'status',
    key: 'status',
    customRender: ({ record }: { record: InvoiceRecord }) =>
      getInvoiceStatusTag(record.status),
  },
  { title: '创建时间', dataIndex: 'createTime', key: 'createTime' },
  { title: '备注', dataIndex: 'remark', key: 'remark' },
];

// 回款记录表格列
const paymentColumns = [
  { title: '回款单号', dataIndex: 'paymentNo', key: 'paymentNo' },
  { title: '回款金额', dataIndex: 'paymentAmount', key: 'paymentAmount' },
  { title: '回款日期', dataIndex: 'paymentDate', key: 'paymentDate' },
  { title: '回款方式', dataIndex: 'paymentMethod', key: 'paymentMethod' },
  {
    title: '状态',
    dataIndex: 'paymentStatus',
    key: 'paymentStatus',
    customRender: ({ record }: { record: PaymentRecord }) =>
      getPaymentStatusTag(record.paymentStatus),
  },
  { title: '创建时间', dataIndex: 'createTime', key: 'createTime' },
];

const opportunityActivityColumns = [
  { title: '活动标题', dataIndex: 'activityTitle', key: 'activityTitle' },
  { title: '活动类型', dataIndex: 'activityType', key: 'activityType' },
  { title: '活动时间', dataIndex: 'activityTime', key: 'activityTime' },
  {
    title: '活动时长(分钟)',
    dataIndex: 'activityDuration',
    key: 'activityDuration',
  },
  { title: '创建人', dataIndex: 'creatorName', key: 'creatorName' },
  { title: '备注', dataIndex: 'remark', key: 'remark' },
  { title: '操作', key: 'viewActivity' },
];

const opportunityStageColumns = [
  { title: '当前阶段', dataIndex: 'currentStage', key: 'currentStage' },
  { title: '目标阶段', dataIndex: 'targetStage', key: 'targetStage' },
  { title: '审批状态', dataIndex: 'approvalStatus', key: 'approvalStatus' },
  { title: '审批意见', dataIndex: 'approvalOpinion', key: 'approvalOpinion' },
  { title: '审批人', dataIndex: 'approverName', key: 'approverName' },
  { title: '申请时间', dataIndex: 'applyTime', key: 'applyTime' },
  { title: '操作', key: 'viewApproval' },
];

const relatedActivitiesByStageList = computed(() => {
  const data = relatedOpportunity.value?.activitiesByStage as
    | Record<string, ActivityRecord[]>
    | undefined;
  if (!data) {
    return [] as ActivityGroup[];
  }

  return Object.entries(data)
    .filter(([, activities]) => Array.isArray(activities))
    .map(([stage, activities]) => ({
      stage,
      activities,
    }));
});

const relatedStageChangeRecords = computed<StageChangeRecord[]>(
  () => relatedOpportunity.value?.stageChangeRecords ?? [],
);

const router = useRouter();
const activityDetailModalRef = ref<InstanceType<typeof ViewActivityModal>>();
const handleBack = () => {
  router.back();
};

const handleViewOpportunityDetail = () => {
  if (!relatedOpportunity.value?.id) {
    return;
  }

  void router.push(`/sale/detail/${relatedOpportunity.value.id}`);
};

const goActivityDetail = (record: ActivityRecord) => {
  activityDetailModalRef.value?.open(record);
};

const goApprovalDetail = (record: StageChangeRecord) => {
  if (record.id) {
    void router.push(`/sale/stage-approval?approvalId=${record.id}`);
  }
};

// 添加回款
const showPaymentModal = ref(false);
const handleAddPayment = () => {
  showPaymentModal.value = true;
};

const handlePaymentSuccess = () => {
  void refetch();
};

// 添加开票
const showInvoiceModal = ref(false);
const handleAddInvoice = () => {
  showInvoiceModal.value = true;
};

const handleInvoiceSuccess = () => {
  void refetch();
};
</script>
