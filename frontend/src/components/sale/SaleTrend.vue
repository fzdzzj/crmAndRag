<template>
  <!-- 销售走势区域 -->
  <Spin :spinning="loading">
    <div class="flex-1 h-[378.79px] p-5 rounded-xl bg-white">
      <div class="flex justify-between mb-4">
        <div class="font-extrabold leading-8">销售趋势</div>
        <Space wrap>
          <Button @click="$emit('set-last-7-days')">近7天</Button>
          <Button @click="$emit('set-last-30-days')">近30天</Button>
          <Button @click="$emit('set-last-12-months')">近12个月</Button>
        </Space>
      </div>
      <div>
        <div
          v-if="contractData || businessData"
          class="flex flex-col gap-3 mt-4"
        >
          <div
            class="flex justify-between items-center p-3 bg-[#f5f5f5] rounded-md"
          >
            <div class="text-sm text-[#666]">商机总量</div>
            <div class="text-base font-semibold text-[#333]">
              {{
                businessData?.totalSalesOpportunityNum?.toLocaleString() ?? '-'
              }}
            </div>
          </div>
          <div
            class="flex justify-between items-center p-3 bg-[#f5f5f5] rounded-md"
          >
            <div class="text-sm text-[#666]">已签约商机</div>
            <div class="text-base font-semibold text-[#333]">
              {{
                businessData?.totalSignOpportunityNum?.toLocaleString() ?? '-'
              }}
            </div>
          </div>
          <div
            class="flex justify-between items-center p-3 bg-[#f5f5f5] rounded-md"
          >
            <div class="text-sm text-[#666]">新增商机</div>
            <div class="text-base font-semibold text-[#333]">
              {{
                businessData?.totalNewSalesOpportunityNum?.toLocaleString() ??
                '-'
              }}
            </div>
          </div>
          <div
            class="flex justify-between items-center p-3 bg-[#f5f5f5] rounded-md"
          >
            <div class="text-sm text-[#666]">签约合同</div>
            <div class="text-base font-semibold text-[#333]">
              {{ contractData?.totalSignContractNum?.toLocaleString() ?? '-' }}
            </div>
          </div>
          <div
            class="flex justify-between items-center p-3 bg-[#f5f5f5] rounded-md"
          >
            <div class="text-sm text-[#666]">签约总额</div>
            <div class="text-base font-semibold text-[#333]">
              ¥{{ formatAmount(contractData?.totalSignAmount) }}
            </div>
          </div>
        </div>
        <Empty v-else description="暂无数据" />
      </div>
    </div>
  </Spin>
</template>

<script setup lang="ts">
import { Button, Space, Spin, Empty } from 'ant-design-vue';
import type { ContractNumVo, SalesNumVo } from '@/api/axios';

defineProps<{
  loading?: boolean;
  contractData?: ContractNumVo;
  businessData?: SalesNumVo;
  filters?: { reportStartTime?: string; reportEndTime?: string };
}>();

defineEmits<{
  'set-last-7-days': [];
  'set-last-30-days': [];
  'set-last-12-months': [];
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
