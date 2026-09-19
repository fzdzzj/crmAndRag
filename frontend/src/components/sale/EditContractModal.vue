<script setup lang="ts">
import { ref, watch } from 'vue';
import {
  Modal,
  Form,
  Input,
  InputNumber,
  DatePicker,
  Select,
  Button,
  Spin,
  message,
} from 'ant-design-vue';
import {
  useQueryContractDetail,
  useUpdateContract,
  useUpdateContractOrder,
} from '@/hooks/useContract';
const open = ref(false);
const contractId = ref<number | undefined>();
const { data: form } = useQueryContractDetail(contractId);
const { mutateAsync: updateContract } = useUpdateContract();
const { mutateAsync: updateContractOrder } = useUpdateContractOrder();
const addOrder = () => {
  editedForm.value?.orders?.push({
    productName: undefined,
    quantity: undefined,
    unitPrice: undefined,
    amount: undefined,
    remark: undefined,
  });
};
const close = () => {
  open.value = false;
  editedForm.value = undefined;
};
const submit = async () => {
  const contract = editedForm.value?.contract;
  const orders = editedForm.value?.orders ?? [];
  if (!contract || typeof contract.totalAmount !== 'number' || contract.totalAmount <= 0) {
    message.error('合同金额必须大于 0');
    return;
  }
  const invalidOrder = orders.some(
    (item) =>
      typeof item.quantity !== 'number' ||
      item.quantity <= 0 ||
      typeof item.unitPrice !== 'number' ||
      item.unitPrice <= 0 ||
      typeof item.amount !== 'number' ||
      item.amount <= 0,
  );
  if (invalidOrder) {
    message.error('订单的数量、单价、金额必须大于 0');
    return;
  }
  await Promise.all([
    contract
      ? updateContract(contract)
      : Promise.resolve(),
    editedForm.value?.orders
      ? updateContractOrder(editedForm.value.orders)
      : Promise.resolve(),
  ]);
  close();
};
defineExpose({
  open: (id: number | undefined) => {
    open.value = true;
    contractId.value = id;
  },
});
const editedForm = ref<NonNullable<typeof form.value>>();
watch(form, (newVal) => {
  editedForm.value = JSON.parse(JSON.stringify(newVal));
});
</script>

<template>
  <Modal v-model:open="open" title="编辑合同" @ok="submit" @cancel="close">
    <Form
      :label-col="{ span: 6 }"
      :wrapper-col="{ span: 18 }"
      layout="horizontal"
    >
      <template v-if="editedForm && editedForm.contract && editedForm.orders">
        <Form.Item label="合同编号">
          <Input v-model:value="editedForm.contract.contractNo" />
        </Form.Item>
        <Form.Item label="合同名称">
          <Input v-model:value="editedForm.contract.contractName" />
        </Form.Item>
          <Form.Item label="合同金额">
            <InputNumber v-model:value="editedForm.contract.totalAmount" :min="0" />
        </Form.Item>
        <Form.Item label="签约日期">
          <DatePicker
            v-model:value="editedForm.contract.signDate"
            value-format="YYYY-MM-DD HH:mm:ss"
            show-time
          />
        </Form.Item>
        <Form.Item label="合同生效日期">
          <DatePicker
            v-model:value="editedForm.contract.startDate"
            value-format="YYYY-MM-DD HH:mm:ss"
            show-time
          />
        </Form.Item>
        <Form.Item label="合同完成时间">
          <DatePicker
            v-model:value="editedForm.contract.endDate"
            value-format="YYYY-MM-DD HH:mm:ss"
            show-time
          />
        </Form.Item>
        <Form.Item label="合同状态">
          <Select v-model:value="editedForm.contract.contractStatus" />
        </Form.Item>
        <template v-for="(item, index) in editedForm.orders" :key="index">
          <h3>订单{{ index + 1 }}</h3>
          <Form.Item label="产品名称">
            <Input v-model:value="item.productName" />
          </Form.Item>
            <Form.Item label="数量">
              <InputNumber v-model:value="item.quantity" :min="0" />
            </Form.Item>
            <Form.Item label="单价">
              <InputNumber v-model:value="item.unitPrice" :min="0" />
            </Form.Item>
            <Form.Item label="金额">
              <InputNumber v-model:value="item.amount" :min="0" />
          </Form.Item>
          <Form.Item label="备注">
            <Input v-model:value="item.remark" />
          </Form.Item>
        </template>
        <Form.Item>
          <Button type="primary" @click="addOrder">添加订单</Button>
        </Form.Item>
      </template>
      <template v-else>
        <div style="text-align: center; margin-top: 20px">
          <Spin />
        </div>
      </template>
    </Form>
  </Modal>
</template>

<style scoped></style>
