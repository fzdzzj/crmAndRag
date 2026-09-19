import type {
  GetContractByIdResponse,
  GetContractResponse,
  GetSalesQueryResponse,
} from '@/api/axios';
import type { TableColumnType } from 'ant-design-vue';
import type { SaleSearchFilters } from '@/hooks/useSale';
import { useBuildFilterColumn } from '@/utils/buildFilterColumn';
import type { Ref } from 'vue';
import { Tag } from 'ant-design-vue';
import { h } from 'vue';

type ArrayItem<T> = T extends readonly (infer U)[] ? U : never;
type SalesOpportunityVO = ArrayItem<
  NonNullable<NonNullable<GetSalesQueryResponse['data']>['records']>
>;
type ContractVO = ArrayItem<
  NonNullable<NonNullable<GetContractResponse['data']>['records']>
>;
type OrderVO = ArrayItem<NonNullable<GetContractByIdResponse['data']>['orders']>;

// 销售阶段映射
export const stageMap: Record<number, string> = {
  0: '种子',
  1: '潜在商机',
  2: '确认商机',
  3: '储备项目',
  4: '立项签约',
  5: '关闭',
};

export const stageOptions = Object.entries(stageMap).map(([key, value]) => ({
  label: value,
  value: Number(key),
}));

// 获取阶段标签颜色
export function getStageColor(stage: number | string): string {
  const colorMap: Record<number | string, string> = {
    0: 'default', // 种子
    1: 'lime', // 潜在商机
    2: 'cyan', // 确认商机
    3: 'blue', // 储备项目
    4: 'purple', // 立项签约
    5: 'red', // 关闭
    种子: 'default', // 种子
    潜在商机: 'lime', // 潜在商机
    确认商机: 'cyan', // 确认商机
    储备项目: 'blue', // 储备项目
    立项签约: 'purple', // 立项签约
    关闭: 'red', // 关闭
  };

  return colorMap[stage] || 'default';
}
// 表格列定义
export const salesColumns: TableColumnType<SalesOpportunityVO>[] = [
  { title: 'ID', dataIndex: 'id', key: 'id', width: 30 },
  {
    title: '销售订单名称',
    dataIndex: 'opportunityName',
    key: 'opportunityName',
  },
  {
    title: '公司名称',
    dataIndex: 'companyName',
    key: 'companyName',
  },
  {
    title: '联系人',
    dataIndex: 'contactName',
    key: 'contactName',
  },
  {
    title: '阶段',
    dataIndex: 'stage',
    key: 'stage',
    customRender: ({ text }) => stageMap[text] || `阶段${text}`,
  },
  {
    title: '金额',
    dataIndex: 'amount',
    key: 'amount',
    customRender: ({ text }) =>
      text ? `¥${Number(text).toLocaleString()}` : '-',
  },
  {
    title: '预计成交',
    dataIndex: 'expectedCloseDate',
    key: 'expectedCloseDate',
    customRender: ({ text }) => text || '-',
  },
  { title: '来源', dataIndex: 'source', key: 'source' },
  { title: '负责人', dataIndex: 'ownerName', key: 'ownerName' },
  // {
  //   title: '创建人',
  //   dataIndex: 'creatorName',
  //   key: 'creatorName',
  // },
  {
    title: '审批人',
    dataIndex: 'approverName',
    key: 'approverName',
  },
  {
    title: '创建时间',
    dataIndex: 'createTime',
    key: 'createTime',
    customRender: ({ text }) => text || '-',
  },
  { title: '操作', key: 'action' },
];

const contractStatusMap: Record<number, string> = {
  0: '预签约',
  1: '已生效',
  2: '已终止',
  3: '已完成',
  4: '已弃用',
};

export const getContractStatusText = (status: number) =>
  contractStatusMap[status] ||
  `未知状态(${status})`;

const formatCurrency = (value: unknown) => {
  if (value === null || value === undefined || value === '') {
    return '-';
  }
  const amount = Number(value);
  if (Number.isNaN(amount)) {
    return '-';
  }
  return `¥${amount.toLocaleString()}`;
};

const formatDateText = (value: unknown) =>
  typeof value === 'string' || typeof value === 'number'
    ? String(value)
    : '-';

export const contractColumns: TableColumnType<ContractVO>[] = [
  {
    title: '合同编号',
    dataIndex: 'contractNo',
    key: 'contractNo',
    customRender: ({ text }) =>
      h('span', { style: { color: '#1890ff' } }, text),
  },
  {
    title: '合同名称',
    dataIndex: 'contractName',
    key: 'contractName',
  },
  {
    title: '销售订单',
    dataIndex: 'opportunityName',
    key: 'opportunityName',
    customRender: ({ text }) =>
      h('span', { style: { color: '#1890ff' } }, text),
  },
  {
    title: '合同金额',
    dataIndex: 'totalAmount',
    key: 'totalAmount',
    customRender: ({ text }) =>
      h(
        'span',
        { style: { color: 'green', fontWeight: 'bold' } },
        formatCurrency(text),
      ),
  },
  {
    title: '签约日期',
    dataIndex: 'signDate',
    key: 'signDate',
    customRender: ({ text }) => formatDateText(text),
  },
  {
    title: '合同生效日期',
    dataIndex: 'startDate',
    key: 'startDate',
    customRender: ({ text }) => formatDateText(text),
  },
  {
    title: '合同完成时间',
    dataIndex: 'endDate',
    key: 'endDate',
    customRender: ({ text }) => formatDateText(text),
  },
  {
    title: '合同状态',
    dataIndex: 'contractStatus',
    key: 'contractStatus',
    customRender: ({ text }) => {
      const normalized =
        typeof text === 'number'
          ? text
          : Number(text === null || text === undefined ? -1 : text);
      return getContractStatusText(Number.isNaN(normalized) ? -1 : normalized);
    },
  },
  {
    title: '负责人',
    dataIndex: 'ownerName',
    key: 'ownerName',
  },
  {
    title: '创建人',
    dataIndex: 'creatorName',
    key: 'creatorName',
  },
  {
    title: '操作',
    key: 'action',
  },
];

export const orderColumns: TableColumnType<OrderVO>[] = [
  {
    title: '订单编号',
    dataIndex: 'id',
    key: 'id',
  },
  {
    title: '产品名称',
    dataIndex: 'productName',
    key: 'productName',
  },
  {
    title: '数量',
    dataIndex: 'quantity',
    key: 'quantity',
  },
  {
    title: '单价',
    dataIndex: 'unitPrice',
    key: 'unitPrice',
    customRender: ({ text }) => formatCurrency(text),
  },
  {
    title: '金额',
    dataIndex: 'amount',
    key: 'amount',
    customRender: ({ text }) => formatCurrency(text),
  },
  {
    title: '备注',
    dataIndex: 'remark',
    key: 'remark',
    customRender: ({ text }) => formatDateText(text),
  },
];

type CreateSaleColumnsParams = {
  filters: Ref<SaleSearchFilters>;
  setFilters: (filters: Partial<SaleSearchFilters>) => void;
};

export function createSaleColumns({
  filters,
  setFilters,
}: CreateSaleColumnsParams): TableColumnType<SalesOpportunityVO>[] {
  const { buildFilterColumn } = useBuildFilterColumn({
    filters,
    setFilters,
  });

  return [
    {
      title: 'ID',
      dataIndex: 'id',
      key: 'id',
      hidden: true,
    },
    {
      title: '销售订单名称',
      dataIndex: 'opportunityName',
      key: 'opportunityName',
      type: 'text',
    },
    {
      title: '公司名称',
      dataIndex: 'companyName',
      key: 'companyName',
      type: 'text',
    },
    {
      title: '联系人',
      dataIndex: 'contactName',
      key: 'contactName',
      type: 'text',
    },
    {
      title: '阶段',
      dataIndex: 'stage',
      key: 'stage',
      type: 'select',
      selectOptions: stageOptions,
      customRender: ({ text }: { text: number }) => {
        const stageText = stageMap[text] || `阶段${text}`;
        const stageColor = getStageColor(text);
        return h(Tag, { color: stageColor }, () => stageText);
      },
    },
    {
      title: '金额',
      dataIndex: 'amount',
      key: 'amount',
      type: 'number',
      customRender: ({ text }: { text: string }) =>
        text ? `¥${Number(text).toLocaleString()}` : '-',
    },
    {
      title: '预计成交日期',
      dataIndex: 'expectedCloseDate',
      key: 'expectedCloseDate',
      type: 'date-range',
      customRender: ({ text }: { text: string }) => text || '-',
    },
    {
      title: '来源',
      dataIndex: 'source',
      key: 'source',
      type: 'text',
    },
    {
      title: '负责人',
      dataIndex: 'ownerName',
      key: 'ownerName',
      type: 'text',
    },
    {
      title: '审批人',
      dataIndex: 'approverName',
      key: 'approverName',
      type: 'text',
    },
    {
      title: '创建时间',
      dataIndex: 'createTime',
      key: 'createTime',
      hidden: true,
      customRender: ({ text }: { text: string }) => text || '-',
    },
    {
      title: '操作',
      key: 'action',
    },
  ]
    .filter((item) => !item.hidden)
    .map((col) =>
      buildFilterColumn({
        title: typeof col.title === 'string' ? col.title : '',
        key: col.key,
        dataIndex: col.dataIndex as Extract<keyof SaleSearchFilters, string>,
        type: col.type as 'text' | 'number' | 'select' | 'date-range',
        selectOptions: col.selectOptions,
        origin: col,
      }),
    );
}
