<template>
  <form action="">
    <input
      v-model="email"
      type="text"
      placeholder="请输入您的邮箱"
      :class="{ 'input-error': error && !email?.trim() }"
      @input="error = ''"
    />
    <input
      v-model="password"
      type="password"
      placeholder="请输入您的密码"
      :class="{ 'input-error': error && !password?.trim() }"
      @input="error = ''"
    />
    <Button class="signup" :loading="loading" @click="handleClick">登录</Button>
    <p v-if="error" class="error-text">{{ error }}</p>
  </form>
</template>

<script setup lang="ts">
import { Button } from 'ant-design-vue';
import { ref } from 'vue';
const error = ref('');
const handleClick = (event: Event) => {
  event.preventDefault(); // 阻止默认行为，如提交表单
  if (!email.value?.trim() || !password.value?.trim()) {
    error.value = !email.value?.trim() && !password.value?.trim()
      ? '请输入邮箱和密码'
      : !email.value?.trim()
        ? '请输入邮箱'
        : '请输入密码';
    return;
  }
  error.value = '';
  emit('login', {
    email: email.value.trim(),
    password: password.value.trim(),
  });
};
const email = defineModel<string>('email');
const password = defineModel<string>('password');
const emit = defineEmits(['login']);
defineProps<{
  loading: boolean;
}>();
</script>

<style scoped>
form {
  display: flex;
  flex-direction: column;
  gap: 20px;
  align-items: center;
  width: 434px;
  height: 300px;
}
input {
  width: 434px;
  height: 57px;
  background-color: rgb(232, 232, 232);
  border: none;
  border-radius: 35px;
  padding-left: 40px;
}
.input-error {
  border: 1px solid #ff4d4f;
}
.error-text {
  margin: 0;
  color: #ff4d4f;
  font-size: 14px;
}
button {
  width: 216px;
  height: 53px;
  background-color: rgb(49, 118, 255);
  color: rgb(248, 250, 255);
  border: none;
  cursor: pointer;
  border-radius: 35px;
  font-size: 25px;
}
.signup {
  margin-top: 20px;
}
</style>
