import { h, type ComputedRef, type Ref } from 'vue';
import { SearchOutlined } from '@ant-design/icons-vue';
import { Tooltip as ATooltip } from 'ant-design-vue';
import type { ColumnType } from 'ant-design-vue/es/table';
import type { FilterDropdownProps } from 'ant-design-vue/es/table/interface';
import type { RawValueType } from 'ant-design-vue/es/vc-select/BaseSelect';
import FilterDropdown from '@/components/filterDropdown/SelectFilterDropdown.vue';
import TextInputFilter from '@/components/filterDropdown/TextInputFilterDropdown.vue';
import NumberInputFilter from '@/components/filterDropdown/NumberInputFilterDropdown.vue';
import DateRangeFilterDropdown from '@/components/filterDropdown/DateRangeFilterDropdown.vue';

type FilterKey<T> = Extract<keyof T, string>;

function getColumnTitleText(title: unknown): string {
  return typeof title === 'string' || typeof title === 'number'
    ? String(title)
    : '';
}

interface BuildFilterOptions {
  title: string;
  dataIndex: string;
  type: 'text' | 'number' | 'select' | 'date-range' | undefined;
  key: string;
  selectOptions?: { label: string; value: string | number }[];
  hidden?: boolean;
  origin?: ColumnType;
  customRender?: ColumnType['customRender'];
  tooltip?: string;
}

interface UseBuildFilterColumnParams<TFilters extends object> {
  filters: ComputedRef<TFilters> | Ref<TFilters>;
  setFilters: (filters: TFilters) => void;
}

export function useBuildFilterColumn<TFilters extends object>({
  filters,
  setFilters,
  }: UseBuildFilterColumnParams<TFilters>) {
  function buildFilterColumn({
    title,
    dataIndex,
    type,
    key,
    selectOptions,
    origin,
    customRender,
    tooltip,
  }: BuildFilterOptions): ColumnType {
    const columnTitle = tooltip
      ? h(ATooltip, { title: tooltip }, () => title)
      : title;
    if (type === undefined || dataIndex === undefined) {
      return {
        ...(origin ?? {}),
        title: columnTitle,
        key,
      };
    }
    const isText = type === 'text';
    return {
      ...origin,
      title: columnTitle,
      dataIndex,
      key,
      ...(customRender ? { customRender } : {}),
      filterIcon: () => {
        const filterKey = dataIndex as FilterKey<TFilters>;
        return h(SearchOutlined, {
          style: {
            color: filters.value[filterKey] ? '#1677ff' : undefined,
          },
        });
      },
      filterDropdown: (props: FilterDropdownProps<TFilters>) => {
        if (isText) {
          return textInputFilterDropdown(props, {
            filters,
            setFilters,
          });
        }
        if (type === 'number') {
          return numberInputFilterDropdown(props, {
            filters,
            setFilters,
          });
        }
        if (type === 'select') {
          return selectFilterDropdown(
            props,
            {
              filters,
              setFilters,
            },
            selectOptions ?? [],
          );
        }
        if (type === 'date-range') {
          return dateRangeFilterDropdown(props, {
            filters,
            setFilters,
          });
        }
      },
    };
  }
  return {
    buildFilterColumn,
  };
}
function textInputFilterDropdown<TFilters extends object>(
  { close, column }: FilterDropdownProps<TFilters>,
  options: UseBuildFilterColumnParams<TFilters>,
) {
  const { filters, setFilters } = options;
  const key = column.dataIndex as FilterKey<TFilters>;
  const filterValue = filters.value[key];

  return h(TextInputFilter, {
    columnTitle: getColumnTitleText(column.title),
    filterValue: typeof filterValue === 'string' ? filterValue : undefined,
    onConfirm: (value: string | undefined) => {
      setFilters({ ...filters.value, [key]: value } as TFilters);
      close();
    },
    onReset: () => {
      setFilters({ ...filters.value, [key]: undefined } as TFilters);
      close();
    },
    onClose: () => {
      close();
    },
  });
}
function numberInputFilterDropdown<TFilters extends object>(
  { close, column }: FilterDropdownProps<TFilters>,
  options: UseBuildFilterColumnParams<TFilters>,
) {
  const { filters, setFilters } = options;
  const key = column.dataIndex as FilterKey<TFilters>;
  const filterValue = filters.value[key];

  return h(NumberInputFilter, {
    columnTitle: getColumnTitleText(column.title),
    filterValue: typeof filterValue === 'number' ? filterValue : undefined,
    onConfirm: (value: number | undefined) => {
      setFilters({ ...filters.value, [key]: value } as TFilters);
      close();
    },
    onReset: () => {
      setFilters({ ...filters.value, [key]: undefined } as TFilters);
      close();
    },
    onClose: () => {
      close();
    },
  });
}

function selectFilterDropdown<TFilters extends object>(
  { close, column }: FilterDropdownProps<TFilters>,
  options: UseBuildFilterColumnParams<TFilters>,
  selectOptions: { label: string; value: string | number }[],
) {
  const { filters, setFilters } = options;
  const key = column.dataIndex as FilterKey<TFilters>;

  // 处理 filterValue：如果是单个值，转换为数组；如果是数组，直接使用；否则使用空数组
  const filterValue = filters.value[key];
  const normalizedFilterValue: RawValueType[] = Array.isArray(filterValue)
    ? filterValue.filter(
        (item): item is RawValueType =>
          typeof item === 'string' || typeof item === 'number',
      )
    : typeof filterValue === 'string' || typeof filterValue === 'number'
      ? [filterValue]
      : [];

  return h(FilterDropdown, {
    selectOptions,
    columnTitle: getColumnTitleText(column.title),
    filterValue: normalizedFilterValue,
    onConfirm: (value?: RawValueType[]) => {
      const normalizedValue = value ?? [];
      // 如果只选择了一个值，保存为单个值；如果选择了多个值，保存为数组
      const newValue =
        normalizedValue.length === 1 ? normalizedValue[0] : normalizedValue;
      setFilters({ ...filters.value, [key]: newValue } as TFilters);
      close();
    },
    onReset: () => {
      setFilters({ ...filters.value, [key]: undefined } as TFilters);
      close();
    },
    onClose: () => {
      close();
    },
  });
}

function dateRangeFilterDropdown<TFilters extends object>(
  { close, column }: FilterDropdownProps<TFilters>,
  options: UseBuildFilterColumnParams<TFilters>,
) {
  const { filters, setFilters } = options;
  const key = column.dataIndex as string;
  const minKey =
    `min${key.charAt(0).toUpperCase()}${key.slice(1)}` as Extract<keyof TFilters, string>;
  const maxKey =
    `max${key.charAt(0).toUpperCase()}${key.slice(1)}` as Extract<keyof TFilters, string>;
  const minValue = filters.value[minKey];
  const maxValue = filters.value[maxKey];

  return h(DateRangeFilterDropdown, {
    columnTitle: getColumnTitleText(column.title),
    minValue: typeof minValue === 'string' ? minValue : undefined,
    maxValue: typeof maxValue === 'string' ? maxValue : undefined,
    onConfirm: (min?: string, max?: string) => {
      setFilters({
        ...filters.value,
        [minKey]: min,
        [maxKey]: max,
      } as TFilters);
      close();
    },
    onReset: () => {
      setFilters({
        ...filters.value,
        [minKey]: undefined,
        [maxKey]: undefined,
      } as TFilters);
      close();
    },
    onClose: () => {
      close();
    },
  });
}
