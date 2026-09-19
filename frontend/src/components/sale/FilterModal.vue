<script lang="ts">
import {
  Button,
  Modal,
  Form,
  Select,
  FormItem,
  DatePicker,
  ConfigProvider,
  SelectOption,
} from 'ant-design-vue';
export default {
  components: {
    'a-form': Form,
    'a-select': Select,
    'a-date-picker': DatePicker,
    'a-button': Button,
    'a-modal': Modal,
    'a-config-provider': ConfigProvider,
    'a-select-option': SelectOption,
    'a-form-item': FormItem,
  },
};
</script>
<script setup lang="ts">
import { reactive, ref } from 'vue';
import zhCN from 'ant-design-vue/es/locale/zh_CN';
const emit = defineEmits<{
  (e: 'refetch'): void;
}>();
const formData = reactive({
  customer: '深圳市科技有限公司',
  saleDate: null,
  projectStatus: 'reserve',
  payment: '未支付',
});
// 筛选相关逻辑
const open = ref(false);
const showModal = () => {
  open.value = true;
};

const handleOk = () => {
  open.value = false;
};
const handleFilter = () => {
  emit('refetch');
  open.value = false;
};
defineExpose({
  showModal,
});
</script>

<template>
  <!-- 弹窗结构 -->
  <a-modal
    v-model:open="open"
    :title="null"
    :footer="null"
    width="800px"
    :mask-closable="false"
    @ok="handleOk"
  >
    <a-config-provider :locale="zhCN">
      <div class="order-filter-wrap">
        <!-- 表单内容 -->
        <a-form :label-col="{ span: 9 }" :wrapper-col="{ span: 15 }">
          <a-form-item>
            <template #label>
              <span class="label-wrap">客户名称</span>
            </template>
            <a-select v-model:value="formData.customer" style="width: 200px">
              <a-select-option value="深圳市科技有限公司"
                >深圳市科技有限公司</a-select-option
              >
            </a-select>
          </a-form-item>

          <a-form-item>
            <template #label>
              <span class="label-wrap">销售日期</span>
            </template>
            <a-date-picker
              style="width: 200px"
              value-format="yyyy-MM-dd HH:mm:ss"
              show-time
            />
          </a-form-item>

          <a-form-item>
            <template #label>
              <span class="label-wrap">项目状态</span>
            </template>
            <a-select
              v-model:value="formData.projectStatus"
              style="width: 200px"
            >
              <a-select-option value="reserve">储备项目</a-select-option>
            </a-select>
          </a-form-item>

          <a-form-item>
            <template #label>
              <span class="label-wrap">支付方式</span>
            </template>
            <a-select style="width: 200px" placeholder="请选择支付方式">
              <a-select-option value="未支付">未支付</a-select-option>
              <a-select-option value="微信支付">微信支付</a-select-option>
              <a-select-option value="支付宝">支付宝</a-select-option>
              <a-select-option value="银行转账">银行转账</a-select-option>
              <a-select-option value="线下支付">线下支付</a-select-option>
            </a-select>
          </a-form-item>

          <a-form-item :wrapper-col="{ offset: 10, span: 14 }">
            <a-button type="primary" size="large" @click="handleFilter"
              >筛选</a-button
            >
          </a-form-item>
        </a-form>
      </div>
    </a-config-provider>
  </a-modal>
</template>
<style scoped>
.order-filter-wrap {
  padding: 24px;
  border-radius: 8px;
}
.label-wrap {
  color: #000;
  font-weight: 400;
  font-size: 16px;
}
</style>
