// A minimal stand-in for Claude / ChatGPT, for end-to-end tests against a relay (local or deployed):
//   node scripts/mcp-client.mjs <relay-url> pair <PAIRING-CODE>   → stores the access token
//   node scripts/mcp-client.mjs <relay-url> tools                  → lists the tools
//   node scripts/mcp-client.mjs <relay-url> call <tool> ['{json}'] → calls a tool, prints the result
// The token is kept in $MCP_TOKEN_FILE (default .mcp-token, gitignored).
import { createHash, randomBytes } from 'node:crypto';
import { readFileSync, writeFileSync } from 'node:fs';

const [base, cmd, ...rest] = process.argv.slice(2);
const TOKEN_FILE = process.env.MCP_TOKEN_FILE ?? '.mcp-token';
const b64url = (buf) => Buffer.from(buf).toString('base64url');
const cookies = (res) => res.headers.getSetCookie().map((c) => c.split(';')[0]).join('; ');

async function pair(code) {
  const redirect = 'http://localhost:9/callback';
  const reg = await fetch(`${base}/oauth/register`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ client_name: 'Test client', redirect_uris: [redirect], token_endpoint_auth_method: 'none' }),
  });
  const client = await reg.json();
  const verifier = b64url(randomBytes(32));
  const challenge = b64url(createHash('sha256').update(verifier).digest());
  const q = new URLSearchParams({
    response_type: 'code', client_id: client.client_id, redirect_uri: redirect, state: 's', scope: 'mcp',
    code_challenge: challenge, code_challenge_method: 'S256', resource: `${base}/mcp`,
  });
  const page = await fetch(`${base}/authorize?${q}`);
  const handle = /name="handle" value="([^"]+)"/.exec(await page.text())?.[1];
  if (!handle) throw new Error(`no pairing page (${page.status})`);
  const posted = await fetch(`${base}/authorize`, {
    method: 'POST', redirect: 'manual',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded', Cookie: cookies(page), Origin: new URL(base).origin },
    body: new URLSearchParams({ handle, code, decision: 'pair' }),
  });
  const location = posted.headers.get('Location');
  if (posted.status !== 302 || !location) throw new Error(`pairing refused (${posted.status})`);
  const tok = await fetch(`${base}/oauth/token`, {
    method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({
      grant_type: 'authorization_code', code: new URL(location).searchParams.get('code'), redirect_uri: redirect,
      client_id: client.client_id, code_verifier: verifier, resource: `${base}/mcp`,
    }),
  });
  const t = await tok.json();
  if (!t.access_token) throw new Error('no access token');
  writeFileSync(TOKEN_FILE, t.access_token, { mode: 0o600 });
  console.log('paired');
}

let id = 0;
async function rpc(method, params = {}) {
  const res = await fetch(`${base}/mcp`, {
    method: 'POST',
    headers: {
      Authorization: `Bearer ${readFileSync(TOKEN_FILE, 'utf8').trim()}`, 'Content-Type': 'application/json',
      Accept: 'application/json, text/event-stream', 'MCP-Protocol-Version': '2025-06-18',
    },
    body: JSON.stringify({ jsonrpc: '2.0', id: ++id, method, params }),
  });
  if (res.status !== 200) throw new Error(`HTTP ${res.status}: ${await res.text()}`);
  return res.json();
}

if (cmd === 'pair') await pair(rest[0]);
else if (cmd === 'tools') console.log((await rpc('tools/list')).result.tools.map((t) => t.name).join('\n'));
else if (cmd === 'call') {
  const r = await rpc('tools/call', { name: rest[0], arguments: rest[1] ? JSON.parse(rest[1]) : {} });
  const text = r.result?.content?.[0]?.text ?? JSON.stringify(r.error);
  console.log(r.result?.isError ? `ERROR: ${text}` : text);
} else {
  console.error('Usage: mcp-client.mjs <relay-url> pair <CODE> | tools | call <tool> [json]');
  process.exit(1);
}
