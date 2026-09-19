<template>
  <div class="login">
    <div class="pt-8">
      <img src="@/assets/logo2.png" width="150" alt="">
      <span class="text-[3rem] text-[#407fff]">CRM客户管理系统</span>
    </div>
    <div class="all">
      <div class="choose">
        <li :class="{ active: isLogin }" @click="showLogin">登录</li>
      </div>
      <div class="show">
        <SignUp v-if="isLogin" v-model:email="email" v-model:password="password" :loading="isPending" @login="login" />
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue';
import SignUp from '@/components/login/SignUp.vue';
import { useLogin } from '@/hooks/useAuth';
import { useRouter } from 'vue-router';
import { message } from 'ant-design-vue';

definePage({
  name: 'login',
  meta: {
    requiresAuth: false,
  },
});

const router = useRouter();
const {
  email,
  password,
  mutate: login,
  isPending,
} = useLogin({
  onSuccess: () => {
    message.success('登录成功');
    const redirect = new URLSearchParams(window.location.search).get(
      'redirect',
    );
    if (redirect) {
      void router.push(redirect);
    } else {
      void router.push('/');
    }
  },
  onError: (error) => {
    message.error(error.message);
  },
});
const isLogin = ref(true);
const showLogin = () => {
  isLogin.value = true;
};
</script>

<style scoped>
.login {
  position: absolute;
  top: 50%;
  /* 垂直方向移到视口中心 */
  left: 50%;
  /* 水平方向移到视口中心 */
  transform: translate(-50%, -50%);
  /* 往回偏移自身宽高的 50% */
  width: 934px;
  height: 645px;
  padding: 15px;
  padding-left: 50px;
  padding-right: 50px;

  background-color: rgb(251, 251, 252);
}

p {
  font-size: 30px;
  color: rgb(60, 125, 254);
}

.all {
  display: flex;
  /* 使导航栏宽度由内容决定 */
  flex-direction: column;
  justify-content: center;
  align-items: center;
  margin-top: 70px;
  /* border-bottom: 2px solid #e5e7eb;  */
}

.choose {
  display: flex;
  justify-content: space-around;
  padding-top: 3px;
  margin-bottom: 10px;
  width: 220px;
  height: 55px;
  font-size: 25px;
  border-bottom: 3px solid rgb(225, 225, 225);
}

li {
  height: 52px;
  line-height: 52px;
  list-style: none;
  color: rgb(49, 118, 255);
}

li:hover {
  cursor: pointer;
}

.active {
  font-weight: 800;
  color: rgb(64, 127, 255);
  border-bottom: 3px solid #409eff;
}

.show {
  margin-top: 20px;
}
</style>
