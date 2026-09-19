// import type { UserVO } from "@/api/axios/Api";
import type { UserSearchFilters } from '@/hooks/useUser';
import { useBuildFilterColumn } from '@/utils/buildFilterColumn';
import { Tag } from 'ant-design-vue';
import type { TableColumnType } from 'ant-design-vue';
import { h, type Ref } from 'vue';
interface UserVO {
  /** @format int64 */
  id?: number;
  realName?: string;
  phone?: string;
  email?: string;
  /** @format int64 */
  deptId?: number;
  /** 部门名称 */
  deptName?: string;
  /** @format int64 */
  roleId?: number;
  //   状态（1-正常，0-冻结，2-离职
  status?: number;
  createTime?: string;
  updateTime?: string;
}
export const statusMap: Record<number, string> = {
  1: '正常',
  0: '冻结',
  2: '离职',
};

/** 在职状态颜色映射 */
export const statusColorMap: Record<number, string> = {
  1: 'success',
  0: 'error',
  2: 'default',
};
type CreateUserColumnsParams = {
  roleMap: Record<number, string>;
  statusMap: Record<number, string>;
  filters: Ref<UserSearchFilters>;
  setFilters: (filters: Partial<UserSearchFilters>) => void;
};
export function createUserColumns({
  roleMap,
  statusMap,
  filters,
  setFilters,
}: CreateUserColumnsParams): TableColumnType<UserVO>[] {
  const { buildFilterColumn } = useBuildFilterColumn({
    filters,
    setFilters,
  });
  return [
    {
      title: '姓名',
      dataIndex: 'realName',
      key: 'realName',
      type: 'text',
    },
    {
      title: '电话',
      dataIndex: 'phone',
      key: 'phone',
      type: 'text',
    },
    {
      title: '邮箱',
      dataIndex: 'email',
      key: 'email',
      type: 'text',
    },
    {
      title: '部门',
      dataIndex: 'deptName',
      key: 'deptName',
      customRender: ({ text }: { text?: string }) => text || '-',
      type: 'text',
    },
    {
      title: '角色',
      dataIndex: 'roleId',
      key: 'roleId',
      customRender: ({ text }: { text: number }) =>
        roleMap[text] || `角色${text}`,
      type: 'select',
      selectOptions: Object.entries(roleMap).map(([key, value]) => ({
        label: value,
        value: Number(key),
      })),
    },
    {
      title: '在职状态',
      dataIndex: 'status',
      key: 'status',
      customRender: ({ text }: { text: number }) =>
        h(
          Tag,
          { color: statusColorMap[text] ?? 'default' },
          () => statusMap[text] || `状态${text}`,
        ),
      type: 'select',
      selectOptions: Object.entries(statusMap).map(([key, value]) => ({
        label: value,
        value: Number(key),
      })),
    },
    {
      title: '操作',
      key: 'action',
    },
    {
      title: '创建时间',
      dataIndex: 'createTime',
      key: 'createTime',
      hidden: true,
    },
    {
      title: '更新时间',
      dataIndex: 'updateTime',
      key: 'updateTime',
      hidden: true,
    },
  ]
    .filter((item) => !item.hidden)
    .map((col) =>
      buildFilterColumn({
        title: typeof col.title === 'string' ? col.title : '',
        key: col.key,
        dataIndex: col.dataIndex as Extract<keyof Omit<UserVO, 'id'>, string>,
        type: col.type as 'text' | 'number' | 'select',
        selectOptions: col.selectOptions,
        origin: col,
      }),
    );
}
