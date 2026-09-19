<template>
  <!-- 付款周期详情，原来的静态页面 -->
  <div class="payment-details-container">
    <div class="payment-header-title">付款周期详情 合同{{ contractId }}</div>

    <div class="payment-content">
      <!-- 首期付款 -->
      <div class="payment-phase">
        <span class="phase-title">首期付款：</span>
        本合同签订，乙方完成《基于分子炼油技术的连续重整装置节能增效系统开发项目详细设计方案》，甲方审查合格且甲方收到乙方提供的符合法律规定的正式等额增值税【专用发票】后
        <span class="highlight">30个工作日内</span
        >，甲方向乙方支付委托开发费用的
        <span class="highlight-blue">30%</span
        >，即人民币大写：贰佰伍拾叁万捌仟柒佰捌拾元整(小写：
        <span class="highlight-blue">2538780.00</span>元)；
      </div>

      <!-- 第二期付款 -->
      <div class="payment-phase">
        <span class="phase-title">第二期付款：</span>
        【乙方全部设备到达甲方指定现场，并经到货验收合格，乙方完成重整装置全流程模型开发】，【且甲方收到乙方提供的符合法律规定的正式等额增值税【专用发票】后
        <span class="highlight">30个工作日内</span
        >】，【甲方向乙方支付委托开发费用的
        <span class="highlight-blue">30%</span
        >】，即人民币大写：【贰佰伍拾叁万捌仟柒佰捌拾元整】(小写：
        <span class="highlight-blue">2538780.00</span>元)；
      </div>

      <!-- 末期付款 -->
      <div class="payment-phase">
        <span class="phase-title">末期付款：</span>
        乙方开发工作结束，甲方书面验收通过其提交的所有工作成果，系统按照技术协议指标要求最终验收合格，且甲方收到乙方提供的符合法律规定的正式等额增值税【专用发票】后
        <span class="highlight">30个工作日内</span
        >，向乙方付清【除质量保证金外的】剩余委托开发费用。
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { useRoute } from 'vue-router';

const route = useRoute();
const routeParams = route.params as Record<string, string | string[] | undefined>;
//路由设置中path: "contract-details/:id"，id为动态参数
//获取URL中的参数ID
const routeId = routeParams.id;
const contractId = Array.isArray(routeId) ? routeId[0] ?? '' : routeId ?? '';

// 根据ID获取合同详情数据
// const contractDetail = await fetchContractDetail(contractId)
</script>

<style scoped>
* {
  margin: 0;
  padding: 0;
  box-sizing: border-box;
  font-family: 'Microsoft YaHei', Arial, sans-serif;
}
.payment-details-container {
  width: 1060px;
  height: 261px;
  padding: 17px 42px 19px 15px;
  background: rgba(245, 245, 245, 1);
}
.payment-header-title {
  font-size: 20px;
  font-weight: 400;
  color: rgba(0, 0, 0, 1);
  margin-bottom: 10px;
}
.payment-content {
  line-height: 23.17px;
  font-size: 16px;
}
.phase-title {
  color: rgba(49, 118, 255, 1);
}
</style>
