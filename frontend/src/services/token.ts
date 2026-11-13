export interface JoinOptions { serverUrl: string; roomName: string; participantName: string }
export async function requestToken(options: JoinOptions, signal: AbortSignal): Promise<{ token: string; serverUrl: string }> {
  // 前端只提交房间与用户信息；签名密钥留在服务端，signal 支持取消和超时。
  const response = await fetch('/api/dev/token', {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, signal,
    body: JSON.stringify({ roomName: options.roomName.trim(), participantName: options.participantName.trim() }),
  });
  // 代理或服务异常可能返回非 JSON 内容，统一交给下方错误处理。
  const body = await response.json().catch(() => null);
  if (!response.ok) throw new Error(body?.error ?? `Token 请求失败 (${response.status})，请检查 Token 服务。`);
  if (typeof body?.token !== 'string' || typeof body?.serverUrl !== 'string') throw new Error('Token 服务返回格式错误。');
  return body;
}
