export interface User {
  id: number;
  username: string;
  online?: boolean;
}
export interface Call {
  id: string;
  callerId: number;
  calleeId: number;
  roomName: string;
  state: 'RINGING' | 'CONNECTING' | 'ACTIVE' | 'ENDED';
  activeAt?: string;
}
// 使用会话级存储保存业务 JWT；不把它放进 WebSocket URL。
let token = sessionStorage.getItem('auth') || '';
export function setToken(value: string) {
  token = value;
  value ? sessionStorage.setItem('auth', value) : sessionStorage.removeItem('auth');
}
// 所有业务请求经过同源代理，并按后端统一的 { data, error } 响应解包。
export async function api<T>(path: string, method = 'GET', body?: unknown): Promise<T> {
  const response = await fetch('/api' + path, {
    method,
    headers: {
      'Content-Type': 'application/json',
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const result = await response.json().catch(() => null);
  if (!response.ok) throw new Error(result?.error || `请求失败 (${response.status})`);
  return result.data as T;
}
