import type { ContactTaskSearchFilters } from '@/hooks/useContactTask';
import { useBuildFilterColumn } from '@/utils/buildFilterColumn';
import { Tag } from 'ant-design-vue';
import type { TableColumnType } from 'ant-design-vue';
import { h, type Ref } from 'vue';
import type { PostContactTaskQueryResponse } from '@/api/axios';

type ContactTaskVO = NonNullable<
  NonNullable<PostContactTaskQueryResponse['data']>['records']
>[number];

/** 联系人关系等级选项 (1-9，9 为最紧密) */
export const relationLevelOptions = Array.from({ length: 9 }, (_, i) => ({
  label: `L${i + 1}`,
  value: i + 1,
}));

/** 备注类型枚举 */
export const RemarkType = {
  PREFERENCE: 1, // 喜好
  ADDRESS: 2, // 住址
  OWN_BIRTHDAY: 3, // 本人生日
  RELATIVE_BIRTHDAY: 4, // 亲属生日
  CUSTOM: 5, // 自定义
} as const;

/** 备注类型描述映射 */
export const remarkTypeMap: Record<number, string> = {
  [RemarkType.PREFERENCE]: '喜好',
  [RemarkType.ADDRESS]: '住址',
  [RemarkType.OWN_BIRTHDAY]: '本人生日',
  [RemarkType.RELATIVE_BIRTHDAY]: '亲属生日',
  [RemarkType.CUSTOM]: '自定义',
};

/** 备注类型 Select 选项 */
export const remarkTypeOptions = Object.entries(remarkTypeMap).map(
  ([value, label]) => ({
    label,
    value: Number(value),
  }),
);

/** 家庭信息相关的备注类型 */
export const familyRemarkTypes = [
  RemarkType.ADDRESS,
  RemarkType.PREFERENCE,
  RemarkType.OWN_BIRTHDAY,
  RemarkType.RELATIVE_BIRTHDAY,
] as const;

export const taskStatusMap: Record<number, string> = {
  0: '待处理',
  1: '进行中',
  2: '已完成',
  3: '已取消',
};

export const taskPriorityMap: Record<number, string> = {
  0: '低',
  1: '中',
  2: '高',
  3: '紧急',
};

/** 任务优先级颜色映射 */
export const taskPriorityColorMap: Record<string, string> = {
  0: 'default',
  1: 'blue',
  2: 'orange',
  3: 'red',
  低: 'default',
  中: 'blue',
  高: 'orange',
  紧急: 'red',
};

/** 任务优先级文案映射（兼容数字与中文字符串两种取值） */
export const taskPriorityLabelMap: Record<string, string> = {
  0: '低',
  1: '中',
  2: '高',
  3: '紧急',
  低: '低',
  中: '中',
  高: '高',
  紧急: '紧急',
};

export const taskTypeMap: Record<string, string> = {
  call: '电话',
  email: '邮件',
  meeting: '会议',
  visit: '拜访',
  other: '其他',
};

type CreateContactTaskColumnsParams = {
  filters: Ref<ContactTaskSearchFilters>;
  setFilters: (filters: ContactTaskSearchFilters) => void;
};

export function createContactTaskColumns({
  filters,
  setFilters,
}: CreateContactTaskColumnsParams): TableColumnType<ContactTaskVO>[] {
  const { buildFilterColumn } = useBuildFilterColumn({
    filters,
    setFilters,
  });

  const columns = [
    {
      title: '任务标题',
      dataIndex: 'taskTitle',
      key: 'taskTitle',
      type: 'text',
    },
    {
      title: '任务内容',
      dataIndex: 'taskContent',
      key: 'taskContent',
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
      title: '销售机会',
      dataIndex: 'opportunityTitle',
      key: 'opportunityTitle',
      type: 'text',
    },
    {
      title: '任务类型',
      dataIndex: 'taskType',
      key: 'taskType',
      type: 'select',
      selectOptions: Object.entries(taskTypeMap).map(([key, value]) => ({
        label: value,
        value: key,
      })),
    },
    {
      title: '任务状态',
      dataIndex: 'status',
      key: 'status',
      type: 'select',
      selectOptions: Object.entries(taskStatusMap).map(([key, value]) => ({
        label: value,
        value: Number(key),
      })),
    },
    {
      title: '优先级',
      dataIndex: 'priority',
      key: 'priority',
      type: 'select',
      selectOptions: Object.entries(taskPriorityMap).map(([key, value]) => ({
        label: value,
        value: Number(key),
      })),
      customRender: ({ text }: { text: string | number }) => {
        const key = String(text ?? '');
        return h(
          Tag,
          { color: taskPriorityColorMap[key] ?? 'default' },
          () => taskPriorityLabelMap[key] ?? String(text ?? '-'),
        );
      },
    },
    {
      title: '截止时间',
      dataIndex: 'deadline',
      key: 'deadline',
      hidden: true,
    },
    {
      title: '创建人',
      dataIndex: 'creatorName',
      key: 'creatorName',
      type: 'text',
    },
    {
      title: '创建时间',
      dataIndex: 'createTime',
      key: 'createTime',
      hidden: true,
    },
    {
      title: '操作',
      key: 'action',
    },
  ] as Array<
    TableColumnType<ContactTaskVO> & {
      type?: 'text' | 'number' | 'select';
      selectOptions?: { label: string; value: string | number }[];
      hidden?: boolean;
    }
  >;

  return columns
    .filter((item) => !item.hidden)
    .map((col) => {
      // 只对筛选器中存在的字段应用筛选功能
      const filterKeys: (keyof ContactTaskSearchFilters)[] = [
        'taskTitle',
        'taskContent',
        'taskType',
        'taskStatus',
        'priority',
        'companyId',
        'contactId',
        'opportunityId',
      ];

      if (
        typeof col.dataIndex === 'string' &&
        filterKeys.includes(col.dataIndex as keyof ContactTaskSearchFilters)
      ) {
        return buildFilterColumn({
          title: typeof col.title === 'string' ? col.title : '',
          key: String(col.key ?? col.dataIndex),
          dataIndex: col.dataIndex as Extract<
            keyof ContactTaskSearchFilters,
            string
          >,
          type: col.type as 'text' | 'number' | 'select',
          selectOptions: col.selectOptions,
          origin: col,
        });
      }

      // 不在筛选器中的字段直接返回原始列对象
      return col;
    });
}
