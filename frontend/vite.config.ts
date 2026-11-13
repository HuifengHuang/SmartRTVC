import { defineConfig, loadEnv } from 'vite';
import { fileURLToPath } from 'node:url';
import vue from '@vitejs/plugin-vue';

export default defineConfig(({ mode }) => {
  // Read server-side config only; never inject these values through define/envPrefix.
  const root = fileURLToPath(new URL('../', import.meta.url));
  const env = loadEnv(mode, root, '');
  const lan = mode === 'lan';
  if (lan && !env.LAN_IP) throw new Error('请先运行 npm run lan:setup -- <局域网IPv4>。');
  return {
    plugins: [vue()],
    server: {
      host: lan ? env.LAN_IP : '127.0.0.1', port: 5173, strictPort: true,
      fs: { deny: ['.env', '.env.*', '*.{crt,pem}', '**/.git/**', '**/deploy/**', '**/dev-token-server/**'] },
      proxy: {
        // 浏览器使用同源 /api 地址，由开发服务器转发到本机 Token 服务。
        '/api': `http://127.0.0.1:${env.TOKEN_PORT || '3001'}`,
        // LiveKit adds /rtc to the supplied URL; preserve it after stripping the prefix.
        '^/livekit(?:/|$)': { target: 'http://127.0.0.1:7880', ws: true, rewrite: path => path.replace(/^\/livekit/, '') || '/' },
      },
    },
  };
});
