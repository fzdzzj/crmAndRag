<script lang="ts" setup>
import { ref } from 'vue';
import { Tabs } from 'ant-design-vue';
import { useInvoiceList, usePaymentList } from '@/hooks/useFinance';
import FinanceTable from '@/components/finance/FinanceTable.vue';
import MainLayout from '@/layout/MainLayout.vue';

definePage({
  name: 'finance',
});

const activeTab = ref('invoice');

// 发票列表
const { data: invoiceData, isLoading: isLoadingInvoice } = useInvoiceList();

// 回款列表
const { data: paymentData, isLoading: isLoadingPayment } = usePaymentList();

// 发票列定义
const invoiceColumns = [
  { title: '发票号', dataIndex: 'invoiceNo', key: 'invoiceNo' },
  { title: '发票金额', dataIndex: 'invoiceAmount', key: 'invoiceAmount' },
  { title: '开票日期', dataIndex: 'invoiceDate', key: 'invoiceDate' },
  { title: '发票类型', dataIndex: 'invoiceType', key: 'invoiceType' },
  { title: '备注', dataIndex: 'remark', key: 'remark' },
  { title: '操作', key: 'action' },
];

// 回款列定义
const paymentColumns = [
  { title: '回款编号', dataIndex: 'paymentNo', key: 'paymentNo' },
  { title: '回款金额', dataIndex: 'paymentAmount', key: 'paymentAmount' },
  { title: '回款日期', dataIndex: 'paymentDate', key: 'paymentDate' },
  { title: '回款方式', dataIndex: 'paymentMethod', key: 'paymentMethod' },
  { title: '回款状态', dataIndex: 'paymentStatus', key: 'paymentStatus' },
  { title: '操作', key: 'action' },
];
</script>

<template>
  <MainLayout>
    <div class="w-full md:p-4">
      <Tabs
        v-model:active-key="activeTab"
        data-tour="finance-tabs"
        type="card"
        :tab-bar-style="{ margin: 0 }"
      >
        <Tabs.TabPane key="invoice" tab="发票列表">
          <FinanceTable
            :data="invoiceData"
            :columns="invoiceColumns"
            :loading="isLoadingInvoice"
            type="invoice"
          />
        </Tabs.TabPane>
        <Tabs.TabPane key="payment" tab="回款列表">
          <FinanceTable
            :data="paymentData"
            :columns="paymentColumns"
            :loading="isLoadingPayment"
            type="payment"
          />
        </Tabs.TabPane>
      </Tabs>
    </div>
  </MainLayout>
</template>
