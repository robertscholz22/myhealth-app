// One-time setup after `npx wrangler deploy`: makes a registration key, stores it as the Worker
// secret REGISTRATION_KEY, and prints the connection code to paste into MyHealth › More › AI planning.
// Usage: node scripts/setup.mjs https://myhealth-relay.<your-subdomain>.workers.dev
// Keep the printed code private: whoever has it can connect a phone to your relay.
import { spawnSync } from 'node:child_process';
import { randomBytes } from 'node:crypto';
import { connectionCode } from './connection-code.mjs';

const url = process.argv[2];
if (!url || !/^https:\/\/[^/]+$/.test(url.replace(/\/+$/, ''))) {
  console.error('Usage: node scripts/setup.mjs https://myhealth-relay.<your-subdomain>.workers.dev');
  process.exit(1);
}
const key = randomBytes(24).toString('base64url');
const put = spawnSync('npx', ['wrangler', 'secret', 'put', 'REGISTRATION_KEY'], { input: key, stdio: ['pipe', 'inherit', 'inherit'] });
if (put.status !== 0) {
  console.error('Could not store the secret; is `npx wrangler login` done and the Worker deployed?');
  process.exit(1);
}
// A check only: the key is stored either way, so the code below must always be printed.
try {
  const res = await fetch(url.replace(/\/+$/, '') + '/');
  if (!res.ok) console.warn(`Warning: ${url} answered ${res.status}; check the URL.`);
} catch (e) {
  console.warn(`Warning: could not reach ${url} (${e.cause?.code ?? e.message}).`);
  console.warn('A new workers.dev address can take a few minutes until its certificate is ready; the code still works then.');
}
console.log('\nConnection code for MyHealth › More › AI planning (keep it private):\n');
console.log(connectionCode(url, key));
