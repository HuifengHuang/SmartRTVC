import { EventEmitter } from 'node:events';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { RoomEvent, Track, type Room } from 'livekit-client';
import { createVoiceRoom } from './voiceRoom';
import { requestToken } from './token';
vi.mock('./token', () => ({ requestToken: vi.fn() }));
let fake: ReturnType<typeof mockRoom>;
let host: HTMLElement;
function mockRoom() {
  const emitter = new EventEmitter();
  const localParticipant = { identity: 'alice', name: 'Alice', isMicrophoneEnabled: false, isSpeaking: false, audioTrackPublications: new Map(), setMicrophoneEnabled: vi.fn(async (enabled: boolean) => { localParticipant.isMicrophoneEnabled = enabled; emitter.emit(RoomEvent.LocalTrackPublished); }) };
  return Object.assign(emitter, { localParticipant, remoteParticipants: new Map(), canPlaybackAudio: false,
    connect: vi.fn(async () => { emitter.emit(RoomEvent.ConnectionStateChanged, 'connected'); }),
    disconnect: vi.fn(async () => {}), startAudio: vi.fn(async () => {}),
  });
}
beforeEach(() => {
  vi.stubGlobal('window', { isSecureContext: true, setTimeout, clearTimeout });
  vi.mocked(requestToken).mockReset().mockResolvedValue({ token: 'test-token', serverUrl: 'ws://localhost:7880' });
  fake = mockRoom(); host = { appendChild: vi.fn(), replaceChildren: vi.fn() } as unknown as HTMLElement;
});
afterEach(() => vi.unstubAllGlobals());
const options = { serverUrl: '', roomName: 'room', participantName: 'Alice' };
const setup = () => createVoiceRoom(host, () => fake as unknown as Room);
it('allows HTTP room join but explains why microphone capture is blocked', async () => {
  vi.stubGlobal('window', { isSecureContext: false, location: { protocol: 'http:' }, setTimeout, clearTimeout });
  const voice = setup(); await voice.join(options);
  expect(voice.state.status).toBe('connected');
  await voice.toggleMicrophone();
  expect(fake.localParticipant.setMicrophoneEnabled).not.toHaveBeenCalled();
  expect(voice.state.error).toContain('HTTP');
  expect(voice.state.micBusy).toBe(false);
  expect(voice.state.status).toBe('connected');
  await voice.leave();
});
it('rejects insecure signaling on HTTPS before connecting', async () => {
  vi.stubGlobal('window', { isSecureContext: true, location: { protocol: 'https:' }, setTimeout, clearTimeout });
  const voice = setup(); await voice.join(options);
  expect(fake.connect).not.toHaveBeenCalled();
  expect(voice.state.error).toContain('ws://');
  expect(voice.state.status).toBe('disconnected');
});
it('uses the LAN WS URL returned by the token service', async () => {
  vi.mocked(requestToken).mockResolvedValue({ token: 'lan-token', serverUrl: 'ws://192.168.1.20:5173/livekit' });
  const voice = setup(); await voice.join(options);
  expect(fake.connect).toHaveBeenCalledWith('ws://192.168.1.20:5173/livekit', 'lan-token', { autoSubscribe: true });
  await voice.leave();
});
it('prevents duplicate joins and toggles the microphone both ways', async () => {
  const voice = setup(); await Promise.all([voice.join(options), voice.join(options)]);
  expect(fake.connect).toHaveBeenCalledTimes(1);
  expect(voice.state.people[0].identity).toBe('alice');
  await voice.toggleMicrophone(); expect(voice.state.microphone).toBe(true);
  await voice.toggleMicrophone(); expect(voice.state.microphone).toBe(false);
  await voice.leave(); expect(fake.disconnect).toHaveBeenCalledWith(true);
  expect(voice.state.people).toEqual([]); expect(fake.eventNames()).toEqual([]);
});
it('cancels an in-flight token request without connecting later', async () => {
  let resolve!: (v: { token: string; serverUrl: string }) => void;
  vi.mocked(requestToken).mockReturnValue(new Promise(r => { resolve = r; }));
  const voice = setup(); const joining = voice.join(options); await voice.leave();
  resolve({ token: 'late', serverUrl: 'ws://localhost:7880' }); await joining;
  expect(fake.connect).not.toHaveBeenCalled(); expect(voice.state.status).toBe('disconnected');
});
it('attaches audio once, detaches on unsubscribe and ignores video', async () => {
  const voice = setup(); await voice.join(options);
  const element = { remove: vi.fn() };
  const audio = { kind: Track.Kind.Audio, attach: vi.fn(() => element), detach: vi.fn(() => [element]) };
  fake.emit(RoomEvent.TrackSubscribed, audio); fake.emit(RoomEvent.TrackSubscribed, audio);
  fake.emit(RoomEvent.TrackSubscribed, { kind: Track.Kind.Video });
  expect(host.appendChild).toHaveBeenCalledTimes(1);
  fake.emit(RoomEvent.TrackUnsubscribed, audio); expect(element.remove).toHaveBeenCalledOnce();
  await voice.leave();
});
it('keeps the room connected when microphone permission is denied', async () => {
  const voice = setup(); await voice.join(options);
  fake.localParticipant.setMicrophoneEnabled.mockRejectedValue(new DOMException('Denied', 'NotAllowedError'));
  await voice.toggleMicrophone(); expect(voice.state.error).toContain('权限被拒绝');
  expect(voice.state.status).toBe('connected'); expect(voice.state.micBusy).toBe(false);
  await voice.leave();
});
it('handles playback restriction and unexpected disconnect', async () => {
  const voice = setup(); await voice.join(options); expect(voice.state.needsAudio).toBe(true);
  fake.canPlaybackAudio = true; await voice.startAudio(); expect(voice.state.needsAudio).toBe(false);
  fake.emit(RoomEvent.Disconnected, 2); expect(voice.state.status).toBe('disconnected');
  expect(voice.state.people).toEqual([]); expect(fake.eventNames()).toEqual([]);
});
