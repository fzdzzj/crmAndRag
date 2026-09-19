import apiClient from '@/api/apiClient.ts';
import { downloadFromBase64 } from '@/utils/download';
import { downloadAttachmentByUrl } from '@/utils/attachment';
import { useMutation } from '@tanstack/vue-query';
import { message } from 'ant-design-vue';
import { postSalesStageApproval, putSalesStage, getSalesStageAttachment } from '@/api/axios';
import type { PostSalesStageApprovalData, PutSalesStageData, GetSalesStageAttachmentData } from '@/api/axios';

//推进销售机会阶段
export function usePushRequestSaleStage() {
  const pushSaleStageMutation = useMutation({
    mutationFn: (
      data: PostSalesStageApprovalData['body'],
    ) => postSalesStageApproval({ client: apiClient, body: data }),
    onSuccess: () => {
      message.success('推进销售机会阶段成功');
    },
  });
  return pushSaleStageMutation;
}
//审批推进请求
export function useApproveSaleStage() {
  const approveSaleStageMutation = useMutation({
    mutationFn: (data: PutSalesStageData['body']) =>
      putSalesStage({ client: apiClient, body: data }),
    onSuccess: () => {
      message.success('审批推进请求成功');
    },
  });
  return approveSaleStageMutation;
}

export type PushRequestSaleStageParams = PostSalesStageApprovalData['body'];
export type ApproveSaleStageParams = PutSalesStageData['body'];

//下载附件
export function useDownloadSaleStageAttachment() {
  const downloadSaleStageAttachmentMutation = useMutation({
    mutationFn: (
      data: GetSalesStageAttachmentData['query'],
    ) => getSalesStageAttachment({ client: apiClient, query: data }),
    onSuccess: (res) => {
      if (res.data?.data) {
        res.data.data.forEach((item) => {
          // 优先使用 downloadUrl
          if (item.downloadUrl) {
            void downloadAttachmentByUrl(item.downloadUrl, item.fileName || '附件');
            return;
          }
          // 如果没有 downloadUrl,使用 fileData
          if (!item.fileData || item.fileData.length === 0) return;
          // 后端契约：fileData 为 base64 字符串（Jackson byte[] 序列化）
          downloadFromBase64(item.fileData, item.fileName || '附件');
        });
      }
    },
  });
  return downloadSaleStageAttachmentMutation;
}
