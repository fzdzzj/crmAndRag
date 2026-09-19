<script setup lang="ts">
import { computed, ref } from 'vue';
import { Button as AButton, Tag as ATag } from 'ant-design-vue';
import { useRouter } from 'vue-router';
import AssistRelatedDetailModal from './AssistRelatedDetailModal.vue';
import type { AssistRelatedType } from '@/hooks/useAssist';

const props = defineProps<{
  assistId?: number;
  modelName?: string;
  recordId?: number;
  opportunityId?: number;
  opportunityName?: string;
  companyId?: number;
  companyName?: string;
  contactId?: number;
  contactName?: string;
  fallback?: unknown;
  snapshot?: string;
  frozen?: boolean;
}>();

const router = useRouter();
const relatedOpen = ref(false);
const relatedType = ref<AssistRelatedType>();

const normalizedModelName = computed(() => props.modelName?.trim().toLowerCase());
const isApproval = computed(() => normalizedModelName.value === 'sales_stage_approval');
const isActivity = computed(() => normalizedModelName.value === 'business_activity');
const isContactTask = computed(() => normalizedModelName.value === 'contact_task');
const hasRelated = computed(() => Boolean(
  props.opportunityId || props.companyId || props.contactId
  || (props.recordId && (isApproval.value || isActivity.value || isContactTask.value)),
));

const openRelated = (type: AssistRelatedType) => {
  relatedType.value = type;
  relatedOpen.value = true;
};

const openOpportunity = () => {
  if (!props.opportunityId) return;
  const query = props.assistId ? { assistId: String(props.assistId) } : undefined;
  void router.push({ path: `/sale/detail/${props.opportunityId}`, query });
};
</script>

<template>
  <div class="mt-3 flex flex-wrap items-center gap-2 border-t pt-3 text-sm">
    <span class="mr-1 text-gray-500">关联记录：</span>

    <AButton
      v-if="opportunityId && !frozen"
      type="link"
      size="small"
      class="!px-1"
      @click="openOpportunity"
    >
      商机：{{ opportunityName || `#${opportunityId}` }}
    </AButton>
    <AButton
      v-if="companyId"
      type="link"
      size="small"
      class="!px-1"
      @click="openRelated('company')"
    >
      公司：{{ companyName || `#${companyId}` }}
    </AButton>
    <AButton
      v-if="contactId"
      type="link"
      size="small"
      class="!px-1"
      @click="openRelated('contact')"
    >
      联系人：{{ contactName || `#${contactId}` }}
    </AButton>
    <AButton
      v-if="isApproval && recordId"
      type="link"
      size="small"
      class="!px-1"
      @click="openRelated('approval')"
    >
      <ATag color="blue">审批记录</ATag>
    </AButton>
    <AButton
      v-if="isActivity && recordId"
      type="link"
      size="small"
      class="!px-1"
      @click="openRelated('activity')"
    >
      <ATag color="cyan">业务活动</ATag>
    </AButton>
    <AButton
      v-if="isContactTask && recordId"
      type="link"
      size="small"
      class="!px-1"
      @click="openRelated('task')"
    >
      <ATag color="purple">联络任务</ATag>
    </AButton>
    <span v-if="!hasRelated" class="text-gray-400">暂无关联记录</span>

    <AssistRelatedDetailModal
      v-model:open="relatedOpen"
      :assist-id="assistId"
      :type="relatedType"
      :fallback="fallback"
      :snapshot="snapshot"
      :frozen="frozen"
      :load-source-attachments="true"
      view-mode="business"
    />
  </div>
</template>
