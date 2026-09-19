import { useMutation, useQuery, useQueryClient } from '@tanstack/vue-query';
import { computed, ref, type Ref } from 'vue';
import apiClient from '@/api/apiClient.ts';
import {
  getUserHandoverStatisticsByUserId,
  postUserHandoverExecute,
  postUserHandoverQuery,
} from '@/api/axios';
import type {
  GetUserHandoverStatisticsByUserIdResponse,
  PostUserHandoverExecuteData,
  PostUserHandoverQueryData,
} from '@/api/axios';

const HANDOVER_QUERY_KEY = 'user-handover';

export type UserHandoverDTO = NonNullable<PostUserHandoverExecuteData['body']>;
export type UserHandoverQueryDTO = NonNullable<PostUserHandoverQueryData['body']>;
export type HandoverStatisticsVO = NonNullable<GetUserHandoverStatisticsByUserIdResponse['data']>[number];

export function useExecuteHandover() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (data: UserHandoverDTO) => {
      const res = await postUserHandoverExecute({ client: apiClient, body: data });
      return res.data?.data;
    },
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: [HANDOVER_QUERY_KEY] });
      void queryClient.invalidateQueries({ queryKey: ['users'] });
    },
  });
}

export function useHandoverRecords(params: Ref<UserHandoverQueryDTO>) {
  return useQuery({
    queryKey: computed(() => [HANDOVER_QUERY_KEY, 'records', params.value]),
    queryFn: async () => {
      const res = await postUserHandoverQuery({ client: apiClient, body: params.value });
      return res.data?.data;
    },
  });
}

export function useUserHandoverRecords(
  fromUserId: Ref<number | undefined>,
  enabledRef?: Ref<boolean>,
) {
  const current = ref(1);
  const pageSize = ref(5);

  const query = useQuery({
    queryKey: computed(() => [
      HANDOVER_QUERY_KEY,
      'records',
      fromUserId.value,
      current.value,
      pageSize.value,
    ]),
    enabled: computed(() => {
      const enabled = enabledRef ? enabledRef.value : true;
      return enabled && !!fromUserId.value;
    }),
    queryFn: async () => {
      const res = await postUserHandoverQuery({
        client: apiClient,
        body: {
          fromUserId: fromUserId.value,
          pageNum: current.value,
          pageSize: pageSize.value,
        },
      });
      return res.data?.data;
    },
  });

  return {
    current,
    pageSize,
    ...query,
  };
}

export function useHandoverStatistics(
  userId: Ref<number | undefined>,
  enabledRef?: Ref<boolean>,
) {
  return useQuery({
    queryKey: computed(() => [HANDOVER_QUERY_KEY, 'statistics', userId.value]),
    enabled: computed(() => {
      const enabled = enabledRef ? enabledRef.value : true;
      return enabled && !!userId.value;
    }),
    queryFn: async () => {
      const res = await getUserHandoverStatisticsByUserId({
        client: apiClient,
        path: { userId: userId.value! },
      });
      return res.data?.data ?? [];
    },
  });
}
