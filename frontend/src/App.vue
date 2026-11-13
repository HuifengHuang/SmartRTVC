<script setup lang="ts">
import { computed, onMounted, onBeforeUnmount, reactive, shallowRef, ref } from 'vue';
import { createVoiceRoom } from './services/voiceRoom';
// 隐藏容器承载远端音频元素；挂载后再交给房间控制器使用。
const audioHost = ref<HTMLElement>();
const controller = shallowRef<ReturnType<typeof createVoiceRoom>>();
const form = reactive({ serverUrl: '', roomName: 'voice-test', participantName: '' });
const microphoneRestricted = !window.isSecureContext;
const state = computed(() => controller.value?.state);
const labels: Record<string, string> = { disconnected: '未连接', connecting: '正在连接', connected: '已连接', reconnecting: '正在重连', signalReconnecting: '信令重连中', disconnecting: '正在离开' };
const locked = computed(() => state.value?.status !== 'disconnected');
// 页面离开或组件卸载时释放连接、音轨和监听器。
const unload = () => { void controller.value?.leave(); };
onMounted(() => { controller.value = createVoiceRoom(audioHost.value!); window.addEventListener('pagehide', unload); });
onBeforeUnmount(() => { window.removeEventListener('pagehide', unload); unload(); });
</script>

<template>
  <main>
    <header><span class="brand">◉ VOICE LAB</span><span class="phase">PHASE 01 / 音频通信验证</span></header>
    <section class="intro"><p class="eyebrow">REAL-TIME CONNECTION</p><h1>让声音，实时相连。</h1><p>进入同一个房间，开始一次简单的双向语音测试。</p></section>
    <div class="layout">
      <section class="panel">
        <div class="section-title"><h2>连接房间</h2><span class="badge" :class="{ online: state?.status === 'connected' }" role="status">{{ labels[state?.status ?? 'disconnected'] }}</span></div>
        <!-- “加入房间”按钮和回车均通过表单提交调用 join，prevent 避免刷新页面。 -->
        <form @submit.prevent="controller?.join(form)">
          <label>LiveKit Server 地址<input v-model="form.serverUrl" :disabled="locked" placeholder="留空使用 Token 服务配置" aria-label="LiveKit Server 地址"/><small>通常无需填写；局域网模式自动使用服务器的 WS 地址</small></label>
          <label>房间名称<input v-model="form.roomName" :disabled="locked" required maxlength="64" placeholder="例如 voice-test"/></label>
          <label>用户名称<input v-model="form.participantName" :disabled="locked" required maxlength="64" placeholder="例如 Alice（每位用户需不同）" autocomplete="off"/></label>
          <div class="actions"><button class="primary" type="submit" :disabled="locked || !controller">{{ state?.busy ? '处理中…' : '加入房间 →' }}</button><button type="button" :disabled="!locked || state?.status === 'disconnecting'" @click="controller?.leave()">{{ state?.status === 'connecting' ? '取消连接' : '离开房间' }}</button></div>
        </form>
        <p v-if="microphoneRestricted" role="status">当前 HTTP 页面可尝试加入房间接收音频；开启麦克风前，请按 deploy/LAN.md 配置浏览器开发测试例外。</p>
        <div class="error" v-if="state?.error" role="alert">{{ state.error }}</div>
      </section>
      <section class="panel room-panel">
        <div class="section-title"><h2>房间现场</h2><span class="count">{{ state?.people.length ?? 0 }} 位参与者</span></div>
        <div class="empty" v-if="!state?.people.length"><div class="wave">▂ ▅ ▃ █ ▃ ▅ ▂</div><h3>等待声音加入</h3><p>连接后，房间中的参与者会显示在这里。</p></div>
        <ul v-else><li v-for="person in state.people" :key="person.identity"><span class="avatar" :class="{ speaking: person.speaking }">{{ person.name.slice(0, 1).toUpperCase() }}</span><div class="person"><strong>{{ person.name }} <small v-if="person.local">（你）</small></strong><small>{{ person.speaking ? '正在说话' : person.microphone ? '麦克风已开启' : '麦克风已关闭' }}</small></div><span>{{ person.microphone ? '●' : '○' }}</span></li></ul>
        <div class="mic-control"><div><strong>本地麦克风</strong><p>{{ state?.microphone ? '已开启 · 正在发布音频' : '已关闭 · 不发送声音' }}</p></div><button :disabled="state?.status !== 'connected' || state?.micBusy" @click="controller?.toggleMicrophone()">{{ state?.micBusy ? '处理中…' : state?.microphone ? '关闭麦克风' : '开启麦克风' }}</button></div>
        <button class="audio-enable" v-if="state?.needsAudio" @click="controller?.startAudio()">启用声音 · 点击允许播放远端音频</button>
      </section>
    </div>
    <aside><strong>两步开始测试</strong><p>① 在两个浏览器窗口中使用相同房间、不同用户名加入。<br/>② 分别开启麦克风并允许访问，建议佩戴耳机以避免回声。</p></aside>
    <footer><span>Vue + LiveKit / WebRTC</span><span>仅限可信开发网络 · 只传输音频</span></footer>
    <div ref="audioHost" hidden></div>
  </main>
</template>
