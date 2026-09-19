<template>
  <!-- 客户分布区域 -->
  <Spin :spinning="loading">
    <div class="flex-1 h-[378.79px] p-5 rounded-xl bg-white">
      <div class="flex justify-between">
        <div class="font-extrabold leading-8">客户分布</div>
        <Space wrap>
          <Button>导出数据</Button>
        </Space>
      </div>
      <div>
        <div v-if="businessData" class="flex flex-col gap-4 mt-4">
          <div class="flex gap-3">
            <div
              class="flex-1 p-4 rounded-lg text-center bg-gradient-to-br from-[#667eea] to-[#764ba2]"
            >
              <div class="text-sm text-white/90 mb-2">商机总量</div>
              <div class="text-2xl font-bold text-white">
                {{
                  businessData.totalSalesOpportunityNum?.toLocaleString() ?? '-'
                }}
              </div>
            </div>
            <div
              class="flex-1 p-4 rounded-lg text-center bg-gradient-to-br from-[#f093fb] to-[#f5576c]"
            >
              <div class="text-sm text-white/90 mb-2">已签约商机</div>
              <div class="text-2xl font-bold text-white">
                {{
                  businessData.totalSignOpportunityNum?.toLocaleString() ?? '-'
                }}
              </div>
            </div>
          </div>
          <div class="flex gap-3">
            <div
              class="flex-1 p-4 rounded-lg text-center bg-gradient-to-br from-[#4facfe] to-[#00f2fe]"
            >
              <div class="text-sm text-white/90 mb-2">新增商机</div>
              <div class="text-2xl font-bold text-white">
                {{
                  businessData.totalNewSalesOpportunityNum?.toLocaleString() ??
                  '-'
                }}
              </div>
            </div>
            <div
              class="flex-1 p-4 rounded-lg text-center bg-gradient-to-br from-[#43e97b] to-[#38f9d7]"
            >
              <div class="text-sm text-white/90 mb-2">签约率</div>
              <div class="text-2xl font-bold text-white">
                {{
                  businessData.totalSalesOpportunityNum
                    ? (
                        ((businessData.totalSignOpportunityNum ?? 0) /
                          businessData.totalSalesOpportunityNum) *
                        100
                      ).toFixed(1) + '%'
                    : '-'
                }}
              </div>
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
import type { SalesNumVo } from '@/api/axios';

defineProps<{
  loading?: boolean;
  businessData?: SalesNumVo;
}>();
</script>
