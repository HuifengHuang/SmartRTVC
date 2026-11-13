import { createTokenServer } from './server.js';

const { LIVEKIT_API_KEY: apiKey, LIVEKIT_API_SECRET: apiSecret, LIVEKIT_URL: serverUrl = 'ws://localhost:7880' } = process.env;
if (!apiKey || !apiSecret || apiSecret.length < 32) throw new Error('请复制 .env.example 为 .env，并配置 API Key 和至少 32 字符的 Secret。');
if (!['ws:', 'wss:'].includes(new URL(serverUrl).protocol)) throw new Error('LIVEKIT_URL 必须为 ws:// 或 wss:// 地址。');
const port = Number(process.env.TOKEN_PORT ?? 3001);
if (!Number.isInteger(port) || port < 1 || port > 65535) throw new Error('TOKEN_PORT 无效。');
const allowedOrigins = process.env.TOKEN_ALLOWED_ORIGINS?.split(',').map(value => {
  const url = new URL(value.trim());
  if (!['http:', 'https:'].includes(url.protocol) || url.origin !== value.trim()) throw new Error('TOKEN_ALLOWED_ORIGINS 必须为逗号分隔的完整 HTTP(S) Origin，不能含路径。');
  return url.origin;
});
const server = createTokenServer({ apiKey, apiSecret, serverUrl, allowedOrigins });
server.requestTimeout = 10_000;
server.on('error', (error) => { console.error('Token 服务启动失败：', error.message); process.exitCode = 1; });
server.listen(port, '127.0.0.1', () => console.log(`开发 Token 服务：http://127.0.0.1:${port}`));
for (const signal of ['SIGINT', 'SIGTERM'] as const) process.on(signal, () => server.close());
