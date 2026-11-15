import { api, type Call } from './api';
export function createSignaling(onCall: (call: Call) => void, onStatus: (online: boolean) => void) {
  let socket: WebSocket | undefined;
  let timer: ReturnType<typeof setInterval> | undefined;
  let generation = 0;
  // 先撤销当前引用，让旧连接迟到的回调无法覆盖新连接状态。
  function close() {
    generation++;
    clearInterval(timer);
    const old = socket;
    socket = undefined;
    old?.close();
    onStatus(false);
  }
  async function connect() {
    close();
    const current = generation;
    // Ticket 只能使用一次；每次重连都通过已认证的 HTTP 接口重新申请。
    const { ticket } = await api<{ ticket: string }>('/ws/ticket', 'POST');
    if (current !== generation) return;
    const target = new WebSocket(
      `${location.protocol === 'https:' ? 'wss:' : 'ws:'}//${location.host}/ws?ticket=${encodeURIComponent(ticket)}`,
    );
    socket = target;
    // 心跳只用于业务在线检测，音频由独立的 LiveKit 连接传输。
    target.onopen = () => {
      if (socket !== target) return;
      onStatus(true);
      timer = setInterval(() => {
        if (target.readyState === WebSocket.OPEN) target.send('ping');
      }, 15000);
    };
    // 只将带通话快照的 CALL_* 事件交给业务层，PONG 不触发状态更新。
    target.onmessage = (event) => {
      if (socket !== target) return;
      try {
        const message = JSON.parse(event.data);
        if (message.type.startsWith('CALL_') && message.data?.id) onCall(message.data);
      } catch {
        /* Ignore invalid frames. */
      }
    };
    target.onclose = () => {
      if (socket === target) {
        clearInterval(timer);
        onStatus(false);
      }
    };
    target.onerror = () => {
      if (socket === target) onStatus(false);
    };
  }
  return { connect, close };
}
