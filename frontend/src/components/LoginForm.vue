<script setup lang="ts">
import { ref } from 'vue';
defineProps<{ busy: boolean }>();
const emit = defineEmits<{ submit: [name: string, password: string, register: boolean] }>();
const username = ref('');
const password = ref('');
</script>
<template>
  <form @submit.prevent="emit('submit', username, password, false)">
    <h2>登录语音通话</h2>

    <label>
      用户名
      <input
        v-model="username"
        required
        pattern="[a-zA-Z0-9_]{3,32}"
        autocomplete="username"
        placeholder="3–32 位字母、数字或下划线"
      />
    </label>

    <label>
      密码
      <input
        v-model="password"
        required
        type="password"
        minlength="8"
        maxlength="64"
        autocomplete="current-password"
      />
    </label>

    <button :disabled="busy">登录</button>
    <button
      type="button"
      :disabled="busy || password.length < 8 || !/^[a-zA-Z0-9_]{3,32}$/.test(username)"
      @click="emit('submit', username, password, true)"
    >
      注册并登录
    </button>
  </form>
</template>
