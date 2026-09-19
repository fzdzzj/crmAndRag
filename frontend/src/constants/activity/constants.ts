import type { ActivitySearchFilters } from '@/hooks/useActivity';
import { useBuildFilterColumn } from '@/utils/buildFilterColumn';
import type { TableColumnType } from 'ant-design-vue';
import type { Ref } from 'vue';

interface BusinessActivityVO {
  id?: number;
  activityTitle?: string;
  activityContent?: string;
  companyId?: number;
  companyName?: string;
  activityTime?: string;
  activityType?: string;
  activityDuration?: number;
  opportunityId?: number;
  createTime?: string;
  remark?: string;
  creatorId?: number;
  creatorName?: string;
  opportunityName?: string;
}

export const activityTypeMap: Record<string, string> = {
  电话沟通: '电话沟通',
  邮件往来: '邮件往来',
  会议洽谈: '会议洽谈',
  现场拜访: '现场拜访',
  商务宴请: '商务宴请',
  其他: '其他',
};

type CreateActivityColumnsParams = {
  filters: Ref<ActivitySearchFilters>;
  setFilters: (filters: Partial<ActivitySearchFilters>) => void;
};

type ActivityColumn = TableColumnType<BusinessActivityVO> & {
  type?: 'text' | 'number' | 'select';
  hidden?: boolean;
  selectOptions?: { label: string; value: string | number }[];
};

export function createActivityColumns({
  filters,
  setFilters,
}: CreateActivityColumnsParams): TableColumnType<BusinessActivityVO>[] {
  const { buildFilterColumn } = useBuildFilterColumn({
    filters,
    setFilters,
  });

  const columns: ActivityColumn[] = [
    {
      title: '活动标题',
      dataIndex: 'activityTitle',
      key: 'activityTitle',
      type: 'text',
    },
    {
      title: '活动内容',
      dataIndex: 'activityContent',
      key: 'activityContent',
      type: 'text',
    },
    {
      title: '活动类型',
      dataIndex: 'activityType',
      key: 'activityType',
      type: 'select',
      selectOptions: Object.entries(activityTypeMap).map(([key, value]) => ({
        label: value,
        value: key,
      })),
    },
    {
      title: '活动时间',
      dataIndex: 'activityTime',
      key: 'activityTime',
      hidden: true,
    },
    {
      title: '活动时长',
      dataIndex: 'activityDuration',
      key: 'activityDuration',
      hidden: true,
    },
    {
      title: '销售机会',
      dataIndex: 'opportunityName',
      key: 'opportunityName',
      type: 'text',
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
      title: '备注',
      dataIndex: 'remark',
      key: 'remark',
      hidden: true,
    },
    {
      title: '操作',
      key: 'action',
    },
  ];

  return columns
    .filter((item) => !item.hidden)
    .map((col) => {
      const filterKeys: (keyof ActivitySearchFilters)[] = [
        'activityTitle',
        'activityContent',
        'companyName',
        'activityType',
        'activityDuration',
        'opportunityName',
        'remark',
        'creatorName',
      ];

      if (
        col.dataIndex &&
        filterKeys.includes(col.dataIndex as keyof ActivitySearchFilters)
      ) {
        return buildFilterColumn({
          title: typeof col.title === 'string' ? col.title : '',
          key: String(col.key ?? col.dataIndex ?? ''),
          dataIndex: col.dataIndex as Extract<
            keyof ActivitySearchFilters,
            string
          >,
          type: col.type,
          selectOptions: col.selectOptions,
          origin: col,
        });
      }

      return col;
    });
}
