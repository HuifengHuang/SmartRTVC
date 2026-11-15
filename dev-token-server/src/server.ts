import { createServer } from 'node:http';
import { AccessToken, TrackSource } from 'livekit-server-sdk';

export interface Config {
  apiKey: string;
  apiSecret: string;
  serverUrl: string;
  allowedOrigins?: string[];
}

export function createTokenServer(config: Config) {
  const origins = new Set(
    config.allowedOrigins ?? ['http://localhost:5173', 'http://127.0.0.1:5173'],
  );
  return createServer(async (req, res) => {
    const reply = (status: number, body: object) => {
      res.writeHead(status, {
        'Content-Type': 'application/json; charset=utf-8',
        'Cache-Control': 'no-store',
      });
      res.end(JSON.stringify(body));
    };
    // 来源白名单用于开发环境的浏览器请求检查，并不替代生产环境的身份认证。
    if (req.headers.origin && !origins.has(req.headers.origin)) {
      reply(403, { error: '页面来源未获允许，请检查 TOKEN_ALLOWED_ORIGINS 配置。' });
      return;
    }
    if (req.url === '/api/health' && req.method === 'GET') {
      reply(200, { status: 'ok' });
      return;
    }
    if (req.url !== '/api/dev/token') {
      reply(404, { error: '接口不存在。' });
      return;
    }
    if (req.method !== 'POST') {
      reply(405, { error: '请使用 POST。' });
      return;
    }
    if (!req.headers['content-type']?.startsWith('application/json')) {
      reply(415, { error: '请发送 JSON。' });
      return;
    }
    try {
      let raw = '';
      // 边读取边限制请求体大小，避免无上限地累积输入。
      for await (const chunk of req) {
        raw += chunk.toString();
        if (Buffer.byteLength(raw) > 4096) {
          reply(413, { error: '请求体过大。' });
          return;
        }
      }
      let body: unknown;
      try {
        body = JSON.parse(raw);
      } catch {
        reply(400, { error: 'JSON 格式错误。' });
        return;
      }
      const { roomName, participantName } = (body ?? {}) as Record<string, unknown>;
      const valid = (v: unknown): v is string =>
        typeof v === 'string' &&
        v.trim().length > 0 &&
        v.trim().length <= 64 &&
        !/[\u0000-\u001f\u007f]/.test(v);
      if (!valid(roomName) || !valid(participantName)) {
        reply(400, { error: '房间和用户名必须为 1–64 个字符，不能包含控制字符。' });
        return;
      }
      // 用户名同时作为房间内唯一身份；同房间的不同用户应使用不同名称。
      const token = new AccessToken(config.apiKey, config.apiSecret, {
        identity: participantName.trim(),
        name: participantName.trim(),
        ttl: '10m',
      });
      // 授权仅限指定房间，可订阅并发布麦克风音频，不允许发布视频或数据消息。
      token.addGrant({
        roomJoin: true,
        room: roomName.trim(),
        canPublish: true,
        canSubscribe: true,
        canPublishData: false,
        canPublishSources: [TrackSource.MICROPHONE],
      });
      reply(200, { token: await token.toJwt(), serverUrl: config.serverUrl });
    } catch {
      reply(500, { error: 'Token 服务内部错误。' });
    }
  });
}
