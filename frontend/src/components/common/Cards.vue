<template>
  <!-- 数据统计卡片区域 -->
  <Spin :spinning="loading">
    <div class="flex justify-between mx-auto mt-9 mb-2.5 gap-2.5 h-[164.53px]">
      <div
        class="flex flex-col justify-around flex-1 bg-white border border-[#ebeef5] rounded-lg p-5"
      >
        <div class="flex justify-between text-[rgb(49,118,255)]">
          <div class="text-sm text-[#909399] mb-2">商机总量</div>
          <UserAddOutlined class="text-[21px]" />
        </div>
        <div class="text-[26px] font-bold text-[#303133] mb-1">
          {{ businessData?.totalSalesOpportunityNum?.toLocaleString() ?? '-' }}
        </div>
        <div class="flex">
          <ArrowUpOutlined class="mt-1 text-[rgb(34,197,94)] text-[21px]" />
          <div class="text-[rgb(34,197,94)] ml-2">
            已签约 {{ businessData?.totalSignOpportunityNum ?? 0 }}
          </div>
        </div>
      </div>
      <div
        class="flex flex-col justify-around flex-1 bg-white border border-[#ebeef5] rounded-lg p-5"
      >
        <div class="flex justify-between text-[rgb(49,118,255)]">
          <div class="text-sm text-[#909399] mb-2">新增商机</div>
          <HeartFilled class="text-[21px]" />
        </div>
        <div class="text-[26px] font-bold text-[#303133] mb-1">
          {{
            businessData?.totalNewSalesOpportunityNum?.toLocaleString() ?? '-'
          }}
        </div>
        <div class="flex">
          <ArrowUpOutlined class="mt-1 text-[rgb(34,197,94)] text-[21px]" />
          <div class="text-[rgb(34,197,94)] ml-2">较上月增长</div>
        </div>
      </div>
      <div
        class="flex flex-col justify-around flex-1 bg-white border border-[#ebeef5] rounded-lg p-5"
      >
        <div class="flex justify-between text-[rgb(49,118,255)]">
          <div class="text-sm text-[#909399] mb-2">签约合同</div>
          <AlignLeftOutlined class="text-[21px]" />
        </div>
        <div class="text-[26px] font-bold text-[#303133] mb-1">
          {{ contractData?.totalSignContractNum?.toLocaleString() ?? '-' }}
        </div>
        <div class="flex">
          <ArrowUpOutlined class="mt-1 text-[rgb(34,197,94)] text-[21px]" />
          <div class="text-[rgb(34,197,94)] ml-2">
            新增 {{ contractData?.totalNewContractNum ?? 0 }}
          </div>
        </div>
      </div>
      <div
        class="flex flex-col justify-around flex-1 bg-white border border-[#ebeef5] rounded-lg p-5"
      >
        <div class="flex justify-between text-[rgb(49,118,255)]">
          <div class="text-sm text-[#909399] mb-2">签约总额</div>
          <UserDeleteOutlined class="text-[21px]" />
        </div>
        <div class="text-[26px] font-bold text-[#303133] mb-1">
          ¥{{ formatAmount(contractData?.totalSignAmount) }}
        </div>
        <div class="flex">
          <ExclamationCircleFilled
            class="mt-1 text-[rgb(235,184,24)] text-[21px]"
          />
          <div class="text-[rgb(235,184,24)] ml-2">统计周期内</div>
        </div>
      </div>
    </div>
  </Spin>
</template>

<script setup lang="ts">
import { Spin } from 'ant-design-vue';
import {
  UserAddOutlined,
  HeartFilled,
  UserDeleteOutlined,
  ExclamationCircleFilled,
  ArrowUpOutlined,
  AlignLeftOutlined,
} from '@ant-design/icons-vue';
import type { ContractNumVo, SalesNumVo } from '@/api/axios';

defineProps<{
  loading?: boolean;
  contractData?: ContractNumVo;
  businessData?: SalesNumVo;
}>();

function formatAmount(amount?: number): string {
  if (amount === undefined || amount === null) return '-';
  if (amount >= 100000000) {
    return (amount / 100000000).toFixed(2) + '亿';
  } else if (amount >= 10000) {
    return (amount / 10000).toFixed(2) + '万';
  }
  return amount.toLocaleString();
}
</script>
