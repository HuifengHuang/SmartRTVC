import { afterAll, beforeAll, describe, expect, it } from 'vitest';
import { TokenVerifier } from 'livekit-server-sdk';
import { createTokenServer } from './server.js';
const config = {
  apiKey: 'testkey',
  apiSecret: 'test-secret-with-at-least-32-characters',
  serverUrl: 'ws://192.168.1.20:5173/livekit',
  allowedOrigins: ['http://localhost:5173', 'http://192.168.1.20:5173'],
};
const server = createTokenServer(config);
let base: string;
beforeAll(async () => {
  await new Promise<void>((resolve) => server.listen(0, '127.0.0.1', resolve));
  const address = server.address();
  if (!address || typeof address === 'string') throw new Error('No port');
  base = `http://127.0.0.1:${address.port}`;
});
afterAll(
  () =>
    new Promise<void>((resolve, reject) =>
      server.close((error) => (error ? reject(error) : resolve())),
    ),
);
const post = (body: unknown, origin = 'http://localhost:5173') =>
  fetch(`${base}/api/dev/token`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Origin: origin },
    body: JSON.stringify(body),
  });
describe('development token API', () => {
  it('accepts the configured LAN HTTP origin and returns WS', async () => {
    const response = await post(
      { roomName: 'room', participantName: 'Bob' },
      'http://192.168.1.20:5173',
    );
    expect(response.status).toBe(200);
    expect((await response.json()).serverUrl).toBe(config.serverUrl);
    expect(
      (await post({ roomName: 'room', participantName: 'Bob' }, 'http://192.168.1.21:5173')).status,
    ).toBe(403);
  });
  it('signs a verifiable, short-lived token scoped to one room and microphone', async () => {
    const response = await post({ roomName: ' test-room ', participantName: ' Alice ' });
    expect(response.status).toBe(200);
    expect(response.headers.get('cache-control')).toBe('no-store');
    const body = await response.json();
    expect(body.serverUrl).toBe(config.serverUrl);
    const claims = await new TokenVerifier(config.apiKey, config.apiSecret).verify(body.token);
    expect(claims.sub).toBe('Alice');
    expect(claims.video).toMatchObject({
      room: 'test-room',
      roomJoin: true,
      canPublish: true,
      canSubscribe: true,
      canPublishData: false,
      canPublishSources: ['microphone'],
    });
    expect(claims.exp! - claims.nbf!).toBe(600);
  });
  it.each([
    {},
    null,
    { roomName: '', participantName: 'A' },
    { roomName: 'r', participantName: 'a'.repeat(65) },
    { roomName: 2, participantName: 'A' },
  ])('rejects invalid inputs: %j', async (body) => {
    expect((await post(body)).status).toBe(400);
  });
  it('rejects foreign browser origins', async () => {
    expect(
      (await post({ roomName: 'r', participantName: 'a' }, 'https://example.com')).status,
    ).toBe(403);
  });
  it('rejects invalid JSON, wrong methods and oversized input', async () => {
    expect((await fetch(`${base}/api/dev/token`)).status).toBe(405);
    expect(
      (
        await fetch(`${base}/api/dev/token`, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: '{',
        })
      ).status,
    ).toBe(400);
    expect((await post({ roomName: 'a'.repeat(5000) })).status).toBe(413);
  });
});
