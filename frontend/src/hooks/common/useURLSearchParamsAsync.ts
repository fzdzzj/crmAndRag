import { unflattenObject, flattenObject } from '@/utils/flattenObject';
import { message } from 'ant-design-vue';
import { computed, watch } from 'vue';
import {
  useRoute,
  useRouter,
  type LocationQueryRaw,
  type LocationQueryValueRaw,
} from 'vue-router';

type QueryWithSearchObject = Record<string, unknown>;

export function createQueryWithURLSearchParamsAsync<
  TOptions extends QueryWithSearchObject | undefined,
  TResult,
>(p: {
  depGetter: (v: TResult) => QueryWithSearchObject;
  queryHook: (options?: TOptions) => TResult;
}) {
  return (...args: [TOptions?]) => {
    const router = useRouter();
    const route = useRoute();
    let routeQuery: QueryWithSearchObject = {};
    try {
      routeQuery = Object.fromEntries(
        Object.entries(route.query).map(([k, v]) => {
          return [k, JSON.parse(String(v))];
        }),
      );
    } catch {
      message.error('无法解析URL查询参数');
    }
    const [options] = args;
    const initialConditions = unflattenObject(routeQuery);
    // 合并 options 和 initialConditions，initialConditions 优先
    const mergedOptions = {
      ...options,
      ...initialConditions,
    };

    const query = p.queryHook(mergedOptions as TOptions);
    const searchObj = computed(() => p.depGetter(query));
    watch(
      () => searchObj.value,
      (newVal) => {
        const flat = flattenObject(newVal);
        const query: LocationQueryRaw = {};
        Object.entries(flat).forEach(([key, value]) => {
          query[key] = JSON.stringify(value) as LocationQueryValueRaw;
        });
        void router.push({ query });
      },
      {
        immediate: true,
      },
    );
    return query;
  };
}
