<script setup lang="ts">
import {
  Modal,
  Button,
  InputNumber,
  Select,
  SelectOption,
  DatePicker,
  message,
} from 'ant-design-vue';
import { Form, Input } from 'ant-design-vue';
import type { Rule } from 'ant-design-vue/es/form';
import { useCreateContract } from '@/hooks/useContract';
import { useQuerySales } from '@/hooks/useSale';
import { validateForm } from '@/utils/formValidate';
import { ref, computed } from 'vue';
import { TokenManager } from '@/utils/token';
const { mutateAsync: createContract, isPending: isCreating } = useCreateContract();
const { data: salesOpportunities } = useQuerySales({ all: true });
const open = ref(false);
const formRef = ref();

const rules: Record<string, Rule[]> = {
  contractName: [{ required: true, message: '请输入合同名称', trigger: 'blur' }],
  opportunityId: [{ required: true, message: '请选择销售订单', trigger: 'change' }],
  signDate: [{ required: true, message: '请选择签约日期', trigger: 'change' }],
};

// 过滤销售订单：只保留储备项目(3)和立项签约(4)阶段
const filteredSalesOpportunities = computed(() => {
  if (!salesOpportunities.value?.records) {
    return [];
  }
  return salesOpportunities.value.records.filter(
    (item) => item.stage === 3 || item.stage === 4
  );
});
type createContractType = {
  contract?: {
    /** 合同编号 */
    contractNo?: string;
    /**
     * 商机ID
     * @format int64
     */
    opportunityId?: number;
    /**
     * 公司ID
     * @format int64
     */
    companyId?: number;
    /** 合同名称 */
    contractName?: string;
    /** 合同金额 */
    totalAmount?: number;
    /** 签约日期 */
    signDate?: string;
    /** 合同生效日期 */
    startDate?: string;
    /** 合同完成时间 */
    endDate?: string;
    /** 合同状态（0预签约/1已生效/2已终止/3已完成/4已弃用） */
    contractStatus?: number;
    /**
     * 负责人id
     * @format int64
     */
    ownerId?: number;
  };
  orders?: {
    /** 产品名称 */
    productName?: string;
    /** 数量 */
    quantity?: number;
    /** 单价 */
    unitPrice?: number;
    /** 金额 */
    amount?: number;
    /** 备注 */
    remark?: string;
  }[];
};
const form = ref<createContractType>({
  contract: {
    contractNo: undefined,
    contractName: undefined,
    opportunityId: undefined,
    companyId: undefined,
    totalAmount: undefined,
    signDate: undefined,
    startDate: undefined,
    endDate: undefined,
    contractStatus: undefined,
    ownerId: undefined,
  },
  orders: [],
});
const addOrder = () => {
  form.value.orders?.push({
    productName: undefined,
    quantity: undefined,
    unitPrice: undefined,
    amount: undefined,
    remark: undefined,
  });
};
const handleCreateContract = async () => {
  if (!(await validateForm(formRef.value))) {
    return;
  }

  if (!form.value.orders || form.value.orders.length === 0) {
    message.error('请至少添加一条订单明细');
    return;
  }

  const invalidOrder = form.value.orders.some(
    (item) =>
      !item.productName ||
      item.quantity == null ||
      item.unitPrice == null ||
      Number(item.quantity) <= 0 ||
      Number(item.unitPrice) <= 0,
  );
  if (invalidOrder) {
    message.error('请完整填写订单明细：产品名称、数量（大于 0）、单价（大于 0）');
    return;
  }

  if (
    form.value.contract?.startDate &&
    form.value.contract?.endDate &&
    form.value.contract.startDate > form.value.contract.endDate
  ) {
    message.error('合同生效日期不能晚于合同完成时间');
    return;
  }

  form.value.orders?.forEach((item) => {
    item.amount = (item.quantity ?? 0) * (item.unitPrice ?? 0);
  });
  form.value.contract!.totalAmount = form.value.orders?.reduce(
    (acc, item) => acc + (item.quantity ?? 0) * (item.unitPrice ?? 0),
    0,
  );
  if (Number(form.value.contract!.totalAmount ?? 0) <= 0) {
    message.error('合同总金额必须大于 0');
    return;
  }
  form.value.contract!.ownerId = TokenManager.getPayload()?.userID;
  try {
    await createContract({
      contract: form.value.contract,
      orders: form.value.orders,
    });
    close();
  } catch {
    // 后端失败：由全局提示，弹窗保持打开
  }
};
const close = () => {
  open.value = false;
};
defineExpose({ open: () => (open.value = true), close });
</script>

<template>
  <Modal
    v-model:open="open"
    title="创建合同"
    :confirm-loading="isCreating"
    @ok="handleCreateContract"
  >
    <Form
      ref="formRef"
      :model="form.contract"
      :rules="rules"
      :label-col="{ span: 6 }"
      :wrapper-col="{ span: 18 }"
      layout="horizontal"
    >
      <Form.Item label="合同名称" name="contractName" required>
        <Input v-model:value="form.contract!.contractName" />
      </Form.Item>
      <!-- 销售订单 -->
      <Form.Item label="销售订单" name="opportunityId" required>
        <Select
          v-model:value="form.contract!.opportunityId"
          style="width: 100%"
          placeholder="请选择销售订单（仅显示储备项目、立项签约阶段）"
        >
          <SelectOption
            v-for="item in filteredSalesOpportunities"
            :key="item?.id"
            :value="item?.id"
            >{{ item?.opportunityName }}</SelectOption
          >
        </Select>
        <div class="text-yellow-500 text-xs mt-1">
          "储备项目"或"立项签约"阶段的销售订单才能创建合同
        </div>
      </Form.Item>
      <Form.Item label="合同总金额">
        <InputNumber
          :value="
            form.orders?.reduce(
              (acc, item) => acc + (item.quantity ?? 0) * (item.unitPrice ?? 0),
              0,
            )
          "
          :min="0"
          readonly
        />
        <span class="text-[#f10505] text-sm">(根据订单金额自动生成)</span>
      </Form.Item>
      <!-- 合同总金额 -->
      <Form.Item label="签约日期" name="signDate" required>
        <DatePicker
          v-model:value="form.contract!.signDate"
          value-format="YYYY-MM-DD HH:mm:ss"
          show-time
        />
      </Form.Item>
      <Form.Item label="合同生效日期">
        <DatePicker
          v-model:value="form.contract!.startDate"
          value-format="YYYY-MM-DD HH:mm:ss"
          show-time
        />
      </Form.Item>
      <Form.Item label="合同完成时间">
        <DatePicker
          v-model:value="form.contract!.endDate"
          value-format="YYYY-MM-DD HH:mm:ss"
          show-time
        />
      </Form.Item>
      <Form.Item label="合同状态">
        <Select
          v-model:value="form.contract!.contractStatus"
          style="width: 100%"
          placeholder="请选择合同状态"
        >
          <SelectOption value="0">预签约</SelectOption>
          <SelectOption value="1">已生效</SelectOption>
          <SelectOption value="2">已终止</SelectOption>
          <SelectOption value="3">已完成</SelectOption>
          <SelectOption value="4">已弃用</SelectOption>
        </Select>
      </Form.Item>
      <template v-for="(item, index) in form.orders" :key="index">
        <h3>订单{{ index + 1 }}</h3>
        <Form.Item label="产品名称">
          <Input v-model:value="item.productName" />
        </Form.Item>
        <Form.Item label="产品数量">
          <InputNumber v-model:value="item.quantity" :min="0" />
        </Form.Item>
        <Form.Item label="产品单价">
          <InputNumber v-model:value="item.unitPrice" :min="0" />
        </Form.Item>
        <Form.Item label="总金额">
            <InputNumber
              :value="(item.quantity ?? 0) * (item.unitPrice ?? 0)"
              :min="0"
              readonly
            />
        </Form.Item>
        <Form.Item label="备注">
          <Input v-model:value="item.remark" />
        </Form.Item>
      </template>
      <Form.Item>
        <Button type="primary" @click="addOrder">添加订单</Button>
      </Form.Item>
    </Form>
  </Modal>
</template>
