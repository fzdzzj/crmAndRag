<template>
  <a-modal
    v-model:open="innerOpen"
    :title="modalTitle"
    @ok="submit"
    @cancel="close"
  >
    <a-form ref="formRef" :model="approvalForm" :rules="rules">
      <!-- 展示销售订单消息 -->
      <a-form-item label="销售订单名称">
        <a-input
          :value="salesInfo?.opportunityName"
          readonly
          :bordered="false"
        />
      </a-form-item>
      <a-form-item label="当前阶段">
        <a-input
          :value="stageMap[salesInfo?.stage as keyof typeof stageMap]"
          readonly
          :bordered="false"
        />
      </a-form-item>
      <a-form-item label="目标阶段">
        <a-input
          :value="
            stageMap[((salesInfo?.stage ?? 0) + 1) as keyof typeof stageMap]
          "
          readonly
          :bordered="false"
        />
      </a-form-item>
      <a-form-item label="审批备注" name="approvalMessage" required>
        <a-textarea
          v-model:value="approvalForm.approvalMessage"
          data-tour="advance-remark"
          placeholder="请输入审批备注（必填）"
          :rows="3"
        />
      </a-form-item>
      <a-form-item label="协助人">
        <AssistApplyEditor v-model:value="assistApplyList" />
      </a-form-item>
      <a-form-item label="推进附件">
        <a-upload
          :file-list="fileList"
          show-upload-list
          :custom-request="handleUpload"
          :multiple="false"
          @remove="handleRemove"
        >
          <a-button
            style="
              padding: 20px;
              display: flex;
              align-items: center;
              justify-content: center;
            "
          >
            <upload-outlined />
            上传文件
          </a-button>
        </a-upload>
      </a-form-item>
    </a-form>
  </a-modal>
</template>
<script setup lang="ts">
import { ref, h } from 'vue';
import {
  Modal as AModal,
  Form as AForm,
  FormItem as AFormItem,
  Upload as AUpload,
  Button as AButton,
  type UploadProps,
  type UploadFile,
  Input as AInput,
  Textarea as ATextarea,
  message,
} from 'ant-design-vue';
import { UploadOutlined } from '@ant-design/icons-vue';
import type {
  GetSalesQueryResponse,
  PostSalesStageApprovalData,
} from '@/api/axios';
import type { Rule } from 'ant-design-vue/es/form';

import { usePushRequestSaleStage } from '@/hooks/useSaleStage';
import { stageMap } from '@/constants/sale/constant';
import { validateForm } from '@/utils/formValidate';
import AssistApplyEditor from '@/components/assist/AssistApplyEditor.vue';
import type { AssistApplyItem } from '@/hooks/useAssist';

type SalesListItem = NonNullable<
  NonNullable<GetSalesQueryResponse['data']>['records']
>[number];
type StageApprovalForm = NonNullable<PostSalesStageApprovalData['body']> &
  Partial<
    Record<
      | `salesStageApproval.assistApplyList[${number}].assistUserId`
      | `salesStageApproval.assistApplyList[${number}].applyPurpose`
      | `salesStageApproval.assistApplyList[${number}].applyRequirement`,
      number | string
    >
  >;

const { mutateAsync: pushRequestSaleStage } = usePushRequestSaleStage();
const innerOpen = ref(false);
const modalTitle = h('span', '申请推进');
const fileList = ref<UploadFile[]>([]);
const assistApplyList = ref<AssistApplyItem[]>([]);
const formRef = ref();
const approvalForm = ref({ approvalMessage: '' });
const rules: Record<string, Rule[]> = {
  approvalMessage: [{ required: true, message: '请输入审批备注', trigger: 'blur' }],
};
const salesInfo = ref<SalesListItem>();
const createForm = (): StageApprovalForm => ({
  'salesStageApproval.opportunityId': undefined,
  'salesStageApproval.targetStage': undefined,
  'salesStageApproval.approverId': undefined,
  'salesStageApproval.message': undefined,
});
const open = (record: SalesListItem) => {
  innerOpen.value = true;
  salesInfo.value = record;
  approvalForm.value.approvalMessage = '';
  form.value = createForm();
  form.value['salesStageApproval.opportunityId'] = record.id;
  form.value['salesStageApproval.targetStage'] =
    typeof record.stage === 'number' ? record.stage + 1 : undefined;
  form.value['salesStageApproval.approverId'] = record.approverId;
};
const handleUpload: UploadProps['customRequest'] = (options) => {
  if (!options) {
    return;
  }
  if (!(options.file instanceof File)) {
    message.error('无效文件');
    return;
  }

  fileList.value = [
    {
      uid:
        'uid' in options.file && typeof options.file.uid === 'string'
          ? options.file.uid
          : `${Date.now()}`,
      name: options.file.name,
      type: options.file.type,
      originFileObj: options.file as UploadFile['originFileObj'],
    },
  ];
  options.onSuccess?.({});
};
const handleRemove = (file: UploadFile) => {
  const index = fileList.value.findIndex((item) => item.uid === file.uid);
  if (index > -1) {
    fileList.value.splice(index, 1);
  }
  return Promise.resolve();
};
const close = () => {
  innerOpen.value = false;
  fileList.value = [];
  assistApplyList.value = [];
  approvalForm.value.approvalMessage = '';
  salesInfo.value = undefined;
  form.value = createForm();
};
const form = ref<StageApprovalForm>(createForm());
const submit = async () => {
  if (!(await validateForm(formRef.value))) {
    return;
  }
  const validList = assistApplyList.value.filter(
    (item) =>
      item.assistUserId != null &&
      item.applyPurpose?.trim() &&
      item.applyRequirement?.trim(),
  );
  if (assistApplyList.value.length > 0 && validList.length !== assistApplyList.value.length) {
    message.warning('每位协助人都需要填写协作目的与协作要求');
    return;
  }
  const requestBody: StageApprovalForm = {
    ...form.value,
    'salesStageApproval.message': approvalForm.value.approvalMessage.trim(),
  };
  validList.forEach((item, index) => {
    requestBody[`salesStageApproval.assistApplyList[${index}].assistUserId`] = item.assistUserId!;
    requestBody[`salesStageApproval.assistApplyList[${index}].applyPurpose`] = item.applyPurpose!.trim();
    requestBody[`salesStageApproval.assistApplyList[${index}].applyRequirement`] = item.applyRequirement!.trim();
  });

  const file = fileList.value.find((item) => item.name && item.originFileObj);
  if (file?.originFileObj) {
    requestBody['approvalAttachment[0].fileData'] = file.originFileObj;
    requestBody['approvalAttachment[0].fileName'] = file.name;
    requestBody['approvalAttachment[0].fileType'] =
      file.type || file.originFileObj.type;
  }

  try {
    await pushRequestSaleStage(requestBody);
    close();
  } catch {
    // 提交失败：由全局提示，弹窗保持打开
  }
};
defineExpose({ open, close, submit, form });
</script>
