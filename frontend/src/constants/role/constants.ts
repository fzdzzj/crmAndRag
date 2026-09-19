import type { RoleSearchFilters } from '@/hooks/useRole';
import { useBuildFilterColumn } from '@/utils/buildFilterColumn';
import type { TableColumnType } from 'ant-design-vue';
import type { Ref } from 'vue';

interface RoleVO {
  /** @format int64 */
  id?: number;
  roleName?: string;
  roleDesc?: string;
}

type CreateRoleColumnsParams = {
  filters: Ref<RoleSearchFilters>;
  setFilters: (filters: Partial<RoleSearchFilters>) => void;
};

type RoleColumn = TableColumnType<RoleVO> & {
  type?: 'text' | 'number' | 'select';
  hidden?: boolean;
  selectOptions?: { label: string; value: string | number }[];
};

export function createRoleColumns({
  filters,
  setFilters,
}: CreateRoleColumnsParams): TableColumnType<RoleVO>[] {
  const { buildFilterColumn } = useBuildFilterColumn({
    filters,
    setFilters,
  });

  const columns: RoleColumn[] = [
    {
      title: '角色名称',
      dataIndex: 'roleName',
      key: 'roleName',
      type: 'text',
    },
    {
      title: '角色描述',
      dataIndex: 'roleDesc',
      key: 'roleDesc',
      type: 'text',
    },
    {
      title: '操作',
      key: 'action',
    },
  ];

  return columns
    .filter((item) => !item.hidden)
    .map((col) =>
      buildFilterColumn({
        title: typeof col.title === 'string' ? col.title : '',
        key: String(col.key ?? col.dataIndex ?? ''),
        dataIndex: col.dataIndex as Extract<keyof Omit<RoleVO, 'id'>, string>,
        type: col.type,
        selectOptions: col.selectOptions,
        origin: col,
      }),
    );
}
