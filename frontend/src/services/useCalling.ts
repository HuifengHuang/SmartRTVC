import { ref, onMounted, onBeforeUnmount, watch } from 'vue';
import { api, setToken, type User, type Call } from './api';
import { createSignaling } from './signaling';
import { createVoiceRoom } from './voiceRoom';
// 页面只调用这里暴露的操作；业务状态、信令和媒体生命周期在此统一协调。
export function useCalling() {
  const user = ref<User>();
  const users = ref<User[]>([]);
  const call = ref<Call>();
  const online = ref(false);
  const error = ref('');
  const busy = ref(false);
  const seconds = ref(0);
  const audio = document.createElement('div');
  document.body.append(audio);
  const voice = createVoiceRoom(audio);
  let joining = '';
  let generation = 0;
  let started = 0;
  // REST 响应和 WebSocket 都可能带来通话快照，统一处理并防止状态回退。
  async function update(next: Call) {
    if (call.value?.id === next.id && call.value.state === 'ENDED' && next.state !== 'ENDED')
      return;
    if (call.value?.id === next.id) {
      const rank = { RINGING: 0, CONNECTING: 1, ACTIVE: 2, ENDED: 3 };
      if (rank[next.state] < rank[call.value.state]) return;
    }
    if (call.value && call.value.id !== next.id && next.state === 'ENDED') return;
    call.value = next;
    if (next.state === 'ENDED') {
      generation++;
      joining = '';
      started = 0;
      await voice.leave();
      return;
    }
    if (next.state === 'ACTIVE' && !started) started = Date.now();
    // 同一通话只加入一次；代次检查让挂断前发出的请求结果失效。
    if (['CONNECTING', 'ACTIVE'].includes(next.state) && joining !== next.id) {
      joining = next.id;
      const current = generation;
      try {
        const credentials = await api<{ token: string; serverUrl: string }>(
          `/calls/${next.id}/token`,
          'POST',
        );
        if (current !== generation) return;
        await voice.join({
          roomName: next.roomName,
          participantName: String(user.value?.id),
          serverUrl: '',
          credentials,
        });
        if (current !== generation) return;
        if (voice.state.status !== 'connected')
          throw new Error(voice.state.error || '媒体连接失败');
        await voice.toggleMicrophone();
      } catch (e) {
        error.value = String(e);
        if (current === generation) await hangup();
      }
    }
  }
  // 业务信令断开时立即停止本地媒体，重新连接后再向后端同步状态。
  const signaling = createSignaling(
    (next) => void update(next),
    (status) => {
      online.value = status;
      if (!status && call.value && call.value.state !== 'ENDED') {
        error.value = '信令连接断开，请重新连接';
        generation++;
        joining = '';
        started = 0;
        void voice.leave();
      }
    },
  );
  async function refresh() {
    users.value = await api<User[]>('/users');
  }
  // 页面操作共用提交锁和错误提示，避免重复点击产生并行请求。
  async function run(action: () => Promise<void>) {
    if (busy.value) return;
    busy.value = true;
    error.value = '';
    try {
      await action();
    } catch (e) {
      error.value = e instanceof Error ? e.message : String(e);
    } finally {
      busy.value = false;
    }
  }
  // 重新申请一次性 Ticket，并查询服务端当前通话，修正页面残留状态。
  async function connect() {
    await signaling.connect();
    await refresh();
    const current = await api<Call | null>('/calls/current');
    if (current) await update(current);
    else if (call.value) await update({ ...call.value, state: 'ENDED' });
  }
  async function login(username: string, password: string, register: boolean) {
    await run(async () => {
      if (register) await api('/auth/register', 'POST', { username, password });
      const result = await api<{ token: string; user: User }>('/auth/login', 'POST', {
        username,
        password,
      });
      setToken(result.token);
      user.value = result.user;
      await connect();
    });
  }
  async function invite(id: number) {
    await run(async () => {
      await update(await api<Call>('/calls', 'POST', { calleeId: id }));
    });
  }
  async function accept() {
    if (call.value)
      await run(async () => {
        await update(await api<Call>(`/calls/${call.value!.id}/accept`, 'POST'));
      });
  }
  // 先停止本地采集，再请求后端结束通话；请求失败保留重试提示。
  async function hangup() {
    const current = call.value;
    generation++;
    joining = '';
    await voice.leave();
    if (current && current.state !== 'ENDED') {
      try {
        await update(await api<Call>(`/calls/${current.id}/hangup`, 'POST'));
      } catch (e) {
        error.value = `挂断请求失败，请重试：${String(e)}`;
      }
    }
  }
  // 即使注销请求失败，也清除本页身份和连接；服务端撤销仍取决于请求成功。
  async function logout() {
    await run(async () => {
      await hangup();
      try {
        await api('/auth/logout', 'POST');
      } finally {
        signaling.close();
        setToken('');
        user.value = undefined;
        call.value = undefined;
      }
    });
  }
  watch(
    () => voice.state.status,
    (next, previous) => {
      if (next === 'disconnected' && previous === 'connected' && call.value?.state !== 'ENDED')
        void hangup();
    },
  );
  // 通话时长从本页首次收到 ACTIVE 开始计数；用户列表通过独立轮询刷新。
  const tick = setInterval(() => {
    seconds.value = started ? Math.floor((Date.now() - started) / 1000) : 0;
  }, 1000);
  const poll = setInterval(() => {
    if (user.value) void refresh().catch(() => {});
  }, 5000);
  const unload = () => {
    signaling.close();
    void voice.leave();
  };
  onMounted(() => {
    window.addEventListener('pagehide', unload);
    if (sessionStorage.getItem('auth'))
      void run(async () => {
        user.value = await api<User>('/users/me');
        await connect();
      });
  });
  // 组件销毁时同时清理定时器、页面事件、WebSocket 和音频 DOM。
  onBeforeUnmount(() => {
    clearInterval(tick);
    clearInterval(poll);
    window.removeEventListener('pagehide', unload);
    unload();
    audio.remove();
  });
  return {
    user,
    users,
    call,
    online,
    error,
    busy,
    seconds,
    voice: voice.state,
    login,
    invite,
    accept,
    hangup,
    logout,
    reconnect: () => run(connect),
    refresh: () => run(refresh),
    toggleMicrophone: voice.toggleMicrophone,
    startAudio: voice.startAudio,
  };
}
