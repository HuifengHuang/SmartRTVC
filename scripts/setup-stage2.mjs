import { randomBytes } from 'node:crypto';
import { existsSync, readFileSync, writeFileSync } from 'node:fs';
if (existsSync('.env.stage2') || existsSync('deploy/livekit.stage2.yaml'))
  throw new Error('第二阶段本地配置已存在，保留原有密钥；请手动检查配置。');
const secret = () => randomBytes(32).toString('hex');
const key = 'dev' + randomBytes(8).toString('hex');
const livekitSecret = secret();
writeFileSync(
  '.env.stage2',
  `DB_PASSWORD=${secret()}
MYSQL_ROOT_PASSWORD=${secret()}
JWT_SECRET=${secret()}
LIVEKIT_API_KEY=${key}
LIVEKIT_API_SECRET=${livekitSecret}
`,
);
const base = readFileSync('deploy/livekit.yaml', 'utf8').split(/^keys:/m)[0];
writeFileSync(
  'deploy/livekit.stage2.yaml',
  `${base}keys:
  ${key}: ${livekitSecret}
webhook:
  api_key: ${key}
  urls:
    - http://backend:8080/api/livekit/webhook
`,
);
console.log('已生成 .env.stage2 和 deploy/livekit.stage2.yaml（仅本地使用）。');
