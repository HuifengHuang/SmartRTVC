<script setup lang="ts">
import LoginForm from './components/LoginForm.vue';
import { useCalling } from './services/useCalling';
const c = useCalling();
const { user, users, call, online, error, busy, seconds, voice } = c;
const labels = {
  RINGING: '呼叫中',
  CONNECTING: '正在建立音频连接',
  ACTIVE: '通话中',
  ENDED: '已结束',
};
</script>
<template>
  <main>
    <h1>SmartRTVC · 语音通话</h1>
    <p v-if="error" role="alert">{{ error }}</p>
    <LoginForm v-if="!user" :busy="busy" @submit="c.login" />
    <template v-else
      ><header>
        <strong>{{ user.username }}（ID {{ user.id }}）</strong>
        <p>信令：{{ online ? '在线' : '已断开' }}</p>
        <button v-if="!online" :disabled="busy" @click="c.reconnect">重新连接</button
        ><button :disabled="busy" @click="c.logout">退出登录</button>
      </header>
      <section>
        <h2>用户列表</h2>
        <button :disabled="busy" @click="c.refresh">刷新</button>
        <ul>
          <li v-for="person in users.filter((p) => p.id !== user!.id)" :key="person.id">
            {{ person.username }} · {{ person.online ? '在线' : '离线' }}
            <button
              :disabled="busy || !online || !person.online || (!!call && call.state !== 'ENDED')"
              @click="c.invite(person.id)"
            >
              呼叫
            </button>
          </li>
        </ul>
      </section>
      <section v-if="call">
        <h2>{{ labels[call.state] }}</h2>
        <p>
          通话对象：{{
            users.find(
              (p) => p.id === (call!.callerId === user!.id ? call!.calleeId : call!.callerId),
            )?.username || '用户'
          }}
        </p>
        <p v-if="call.state === 'RINGING' && call.calleeId === user.id">收到来电邀请</p>
        <button
          v-if="call.state === 'RINGING' && call.calleeId === user.id"
          :disabled="busy || !online"
          @click="c.accept"
        >
          接听</button
        ><button v-if="call.state !== 'ENDED'" @click="c.hangup">挂断</button>
        <p>通话时长：{{ seconds }} 秒 · 媒体：{{ voice.status }}</p>
        <button
          :disabled="voice.status !== 'connected' || voice.micBusy"
          @click="c.toggleMicrophone"
        >
          {{ voice.microphone ? '关闭麦克风' : '开启麦克风' }}</button
        ><button v-if="voice.needsAudio" @click="c.startAudio">启用声音</button>
        <p v-if="voice.error" role="alert">{{ voice.error }}</p>
      </section>
    </template>
  </main>
</template>
