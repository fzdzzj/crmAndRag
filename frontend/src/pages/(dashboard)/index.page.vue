<template>
  <div></div>
</template>

<script setup lang="ts">
import { onMounted } from 'vue';
import { useRouter } from 'vue-router';

definePage({
  name: 'home',
});

const router = useRouter();

onMounted(() => {
  void router.replace('/client/list');
});
</script>
