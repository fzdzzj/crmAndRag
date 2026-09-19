import { useMutation, useQuery, useQueryClient } from '@tanstack/vue-query';
import { computed, type Ref } from 'vue';
import { message } from 'ant-design-vue';
import apiClient from '@/api/apiClient.ts';
import {
  deleteProjectFile,
  getProjectFileListActivityByActivityId,
  getProjectFileListContractByContractId,
  getProjectFileListOpportunityByOpportunityId,
  postProjectFileUploadStandalone,
} from '@/api/axios';
import type {
  DeleteProjectFileData,
  GetProjectFileListActivityByActivityIdResponse,
  PostProjectFileUploadStandaloneData,
} from '@/api/axios';

const PROJECT_FILE_QUERY_KEY = 'project-file';

export type ProjectFileRecord = NonNullable<
  GetProjectFileListActivityByActivityIdResponse['data']
>[number];

export interface UploadProjectFilePayload {
  category: string;
  file: File;
  theme?: string;
  description?: string;
  opportunityId?: number;
  contractId?: number;
}

export const projectFileCategoryMap: Record<string, string> = {
  VISIT_RECORD: '拜访记录',
  MEETING_MINUTES: '交流纪要',
  PROPOSAL: '方案',
  BID_DOCUMENT: '投标文件',
  PROJECT_CONTRACT: '项目合同',
};

export const projectFileCategoryOptions = Object.entries(projectFileCategoryMap).map(
  ([value, label]) => ({ value, label }),
);

export function useProjectFilesByOpportunity(opportunityId: Ref<number | undefined>) {
  return useQuery({
    queryKey: computed(() => [PROJECT_FILE_QUERY_KEY, 'opportunity', opportunityId.value]),
    enabled: computed(() => !!opportunityId.value),
    queryFn: async () => {
      const res = await getProjectFileListOpportunityByOpportunityId({
        client: apiClient,
        path: { opportunityId: opportunityId.value! },
      });
      return res.data?.data ?? [];
    },
  });
}

export function useProjectFilesByActivity(activityId: Ref<number | undefined>) {
  return useQuery({
    queryKey: computed(() => [PROJECT_FILE_QUERY_KEY, 'activity', activityId.value]),
    enabled: computed(() => !!activityId.value),
    queryFn: async () => {
      const res = await getProjectFileListActivityByActivityId({
        client: apiClient,
        path: { activityId: activityId.value! },
      });
      return res.data?.data ?? [];
    },
  });
}

export function useProjectFilesByContract(contractId: Ref<number | undefined>) {
  return useQuery({
    queryKey: computed(() => [PROJECT_FILE_QUERY_KEY, 'contract', contractId.value]),
    enabled: computed(() => !!contractId.value),
    queryFn: async () => {
      const res = await getProjectFileListContractByContractId({
        client: apiClient,
        path: { contractId: contractId.value! },
      });
      return res.data?.data ?? [];
    },
  });
}

export function useUploadProjectFile() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (payload: UploadProjectFilePayload) => {
      const body: Record<string, unknown> = {
        'files[0].category': payload.category,
        'files[0].fileData': payload.file,
      };
      if (payload.theme) {
        body['files[0].theme'] = payload.theme;
      }
      if (payload.description) {
        body['files[0].description'] = payload.description;
      }
      if (payload.opportunityId) {
        body['files[0].opportunityId'] = payload.opportunityId;
      }
      if (payload.contractId) {
        body['files[0].contractId'] = payload.contractId;
      }

      const res = await postProjectFileUploadStandalone({
        client: apiClient,
        body: body as NonNullable<PostProjectFileUploadStandaloneData['body']>,
      });
      return res.data?.data;
    },
    onSuccess: () => {
      message.success('文件上传成功');
      void queryClient.invalidateQueries({ queryKey: [PROJECT_FILE_QUERY_KEY] });
    },
  });
}

export function useDeleteProjectFile() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (ids: number[]) => {
      const res = await deleteProjectFile({
        client: apiClient,
        query: { ids } satisfies DeleteProjectFileData['query'],
      });
      return res.data?.data;
    },
    onSuccess: () => {
      message.success('删除成功');
      void queryClient.invalidateQueries({ queryKey: [PROJECT_FILE_QUERY_KEY] });
    },
  });
}
