import { reactive } from 'vue';
import { ConnectionState, Room, RoomEvent, Track, type RemoteTrack } from 'livekit-client';
import { requestToken, type JoinOptions } from './token';

export interface Person { identity: string; name: string; local: boolean; microphone: boolean; speaking: boolean }
export function createVoiceRoom(audioHost: HTMLElement, makeRoom = () => new Room({
  // Server v1.9.9 supports /rtc, not /rtc/v1. Skip the SDK's v1 probe/fallback.
  singlePeerConnection: false,
  audioCaptureDefaults: { echoCancellation: true, noiseSuppression: true, autoGainControl: true },
  publishDefaults: { stopMicTrackOnMute: true },
})) {
  const state = reactive({ status: 'disconnected' as string, busy: false, micBusy: false, microphone: false, needsAudio: false, error: '', people: [] as Person[] });
  let room: Room | undefined;
  // 每次加入或离开都会更新代次，让旧连接的异步结果失效，避免覆盖新房间状态。
  let generation = 0;
  let abort: AbortController | undefined;
  const tracks = new Set<RemoteTrack>();
  const detach = (track: RemoteTrack) => { track.detach().forEach(el => el.remove()); tracks.delete(track); };
  // 清理事件监听和远端音频元素；实际断开连接由调用方负责。
  const clear = (target: Room) => {
    target.removeAllListeners();
    tracks.forEach(detach);
    audioHost.replaceChildren();
    state.people = []; state.microphone = false; state.needsAudio = false; state.micBusy = false;
  };
  const message = (error: unknown) => {
    if (error instanceof Error && error.name === 'NotAllowedError') return '麦克风权限被拒绝，请在浏览器地址栏允许麦克风后重试。';
    if (error instanceof Error && error.name === 'NotFoundError') return '没有可用麦克风，请连接设备后重试。';
    if (error instanceof Error && error.name === 'NotReadableError') return '麦克风无法读取，可能被其他程序占用。';
    return error instanceof Error ? error.message : '操作失败，请检查服务与网络。';
  };
  async function leave() {
    if (state.status === 'disconnecting') return;
    generation++; abort?.abort(); abort = undefined;
    const old = room; room = undefined;
    state.busy = true; state.status = 'disconnecting';
    if (old) clear(old);
    try { await old?.disconnect(true); }
    catch (error) { state.error = message(error); }
    finally { state.status = 'disconnected'; state.busy = false; }
  }
  // 加入流程：校验输入 → 绑定房间事件 → 获取 Token → 建立 LiveKit 连接。
  // 加入后仅接收音频，麦克风需要用户单独开启。
  async function join(options: JoinOptions) {
    if (state.busy || room) return;
    state.error = '';
    if (!options.roomName.trim() || !options.participantName.trim()) { state.error = '请输入房间名称和用户名称。'; return; }
    if (options.serverUrl.trim()) {
      try { if (!['ws:', 'wss:'].includes(new URL(options.serverUrl.trim()).protocol)) throw new Error(); }
      catch { state.error = 'LiveKit 地址必须以 ws:// 或 wss:// 开头。'; return; }
    }
    const current = ++generation;
    state.busy = true; state.status = 'connecting';
    const target = makeRoom(); room = target;
    // 将 SDK 的参与者状态转换成 Vue 响应式数据，统一刷新列表和麦克风状态。
    const sync = () => {
      if (room !== target) return;
      state.microphone = target.localParticipant.isMicrophoneEnabled;
      state.people = [target.localParticipant, ...target.remoteParticipants.values()].map(p => ({
        identity: p.identity, name: p.name || p.identity, local: p === target.localParticipant,
        microphone: p.isMicrophoneEnabled, speaking: p.isSpeaking,
      }));
    };
    target.on(RoomEvent.ConnectionStateChanged, status => { if (room === target) state.status = status; });
    for (const event of [RoomEvent.ParticipantConnected, RoomEvent.ParticipantDisconnected, RoomEvent.TrackMuted, RoomEvent.TrackUnmuted, RoomEvent.LocalTrackPublished, RoomEvent.LocalTrackUnpublished, RoomEvent.ActiveSpeakersChanged, RoomEvent.TrackPublished, RoomEvent.TrackUnpublished]) target.on(event, sync);
    // 订阅成功后挂载音频元素才能播放；集合用于去重并在退出时统一移除。
    target.on(RoomEvent.TrackSubscribed, track => {
      if (room !== target || track.kind !== Track.Kind.Audio || tracks.has(track)) return;
      tracks.add(track); audioHost.appendChild(track.attach()); sync();
    });
    target.on(RoomEvent.TrackUnsubscribed, track => { detach(track); sync(); });
    target.on(RoomEvent.AudioPlaybackStatusChanged, () => { state.needsAudio = !target.canPlaybackAudio; });
    target.on(RoomEvent.MediaDevicesError, error => { state.error = message(error); });
    target.on(RoomEvent.Disconnected, reason => {
      if (room !== target) return;
      generation++; abort?.abort(); room = undefined; clear(target);
      state.status = 'disconnected'; state.busy = false;
      state.error = `连接已断开（原因：${reason ?? '未知'}），请检查网络、服务或是否使用了重复用户名。`;
    });
    const requestAbort = new AbortController();
    abort = requestAbort;
    // 此超时只覆盖 Token 请求；离开房间时也会通过同一个控制器取消请求。
    const timeout = window.setTimeout(() => requestAbort.abort(), 15000);
    try {
      const credentials = await requestToken(options, requestAbort.signal);
      window.clearTimeout(timeout);
      if (current !== generation) return;
      const serverUrl = options.serverUrl.trim() || credentials.serverUrl;
      if (window.location?.protocol === 'https:' && new URL(serverUrl).protocol !== 'wss:') throw new Error('HTTPS 页面不能连接 ws:// 信令；当前开发模式请使用 http:// 页面地址。');
      await target.connect(serverUrl, credentials.token, { autoSubscribe: true });
      // 连接等待期间可能已被取消；即使连接成功，也必须立即释放过期房间。
      if (current !== generation) { await target.disconnect(true); return; }
      sync(); state.needsAudio = !target.canPlaybackAudio;
    } catch (error) {
      if (current === generation) {
        state.error = error instanceof Error && error.name === 'AbortError' ? '连接请求超时，请检查 Token 服务。' : message(error);
        room = undefined; clear(target);
        try { await target.disconnect(true); } finally { state.status = 'disconnected'; }
      }
    } finally {
      window.clearTimeout(timeout);
      if (current === generation) { state.busy = false; abort = undefined; }
    }
  }
  async function toggleMicrophone() {
    const target = room;
    if (!target || state.status !== ConnectionState.Connected || state.micBusy) return;
    // 非安全上下文仍可收听，但浏览器会限制麦克风采集。
    if (!target.localParticipant.isMicrophoneEnabled && !window.isSecureContext) {
      state.error = '浏览器禁止 HTTP 局域网页面使用麦克风。请按 deploy/LAN.md 为当前页面地址配置 Chrome/Edge 开发测试例外并重启浏览器；仍可留在房间接收音频。';
      return;
    }
    state.error = ''; state.micBusy = true;
    try {
      await target.localParticipant.setMicrophoneEnabled(!target.localParticipant.isMicrophoneEnabled);
      // 用户授权麦克风期间可能已经离开，停止迟到的音轨，避免继续占用设备。
      if (room !== target) { target.localParticipant.audioTrackPublications.forEach(p => p.track?.stop()); return; }
      state.microphone = target.localParticipant.isMicrophoneEnabled;
    } catch (error) { if (room === target) state.error = message(error); }
    finally { if (room === target) state.micBusy = false; }
  }
  // 自动播放受限时，由用户点击按钮触发，满足浏览器的交互要求。
  async function startAudio() {
    const target = room;
    try { await target?.startAudio(); if (target && room === target) state.needsAudio = !target.canPlaybackAudio; }
    catch (error) { state.error = message(error); }
  }
  return { state, join, leave, toggleMicrophone, startAudio };
}
