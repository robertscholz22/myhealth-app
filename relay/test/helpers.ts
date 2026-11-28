import { exports } from 'cloudflare:workers';
import snapshotJson from './fixtures/snapshot.json';

export const ORIGIN = 'https://relay.test';
export const REG_KEY = 'test-registration-key-0123456789';
export const snapshot = snapshotJson as unknown as import('../src/types').Snapshot;

export function call(path: string, init: RequestInit = {}): Promise<Response> {
  return (exports as any).default.fetch(new Request(ORIGIN + path, init));
}

export async function register(): Promise<{ spaceId: string; spaceToken: string }> {
  const res = await call('/v1/space', { method: 'POST', headers: { Authorization: `Bearer ${REG_KEY}` } });
  if (res.status !== 200) throw new Error(`register ${res.status}`);
  return res.json();
}

export const auth = (token: string) => ({ Authorization: `Bearer ${token}` });

export async function putSnapshot(token: string, body: unknown = snapshot): Promise<Response> {
  return call('/v1/snapshot', { method: 'PUT', headers: { ...auth(token), 'Content-Type': 'application/json' }, body: JSON.stringify(body) });
}

const b64url = (bytes: ArrayBuffer | Uint8Array) =>
  btoa(String.fromCharCode(...new Uint8Array(bytes))).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');

/** Cookies a response set, as one Cookie header value. */
function cookies(res: Response): string {
  return res.headers.getSetCookie().map((c) => c.split(';')[0]).join('; ');
}

/**
 * The connector flow an AI app runs: dynamic client registration, /authorize with PKCE, the pairing
 * page (code typed by the owner), the code exchange. Returns the MCP access token.
 */
export async function pairConnector(spaceToken: string, opts: { code?: string } = {}): Promise<{ accessToken?: string; page: Response }> {
  const redirect = 'https://claude.example/api/mcp/auth_callback';
  const reg = await call('/oauth/register', {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ client_name: 'Claude', redirect_uris: [redirect], token_endpoint_auth_method: 'none' }),
  });
  const client = (await reg.json()) as { client_id: string };
  const verifier = b64url(crypto.getRandomValues(new Uint8Array(32)));
  const challenge = b64url(await crypto.subtle.digest('SHA-256', new TextEncoder().encode(verifier)));
  const q = new URLSearchParams({
    response_type: 'code', client_id: client.client_id, redirect_uri: redirect, state: 'st-1', scope: 'mcp',
    code_challenge: challenge, code_challenge_method: 'S256', resource: `${ORIGIN}/mcp`,
  });
  const page = await call(`/authorize?${q}`);
  const html = await page.clone().text();
  const handle = /name="handle" value="([^"]+)"/.exec(html)?.[1];
  if (!handle) return { page };
  let code = opts.code;
  if (code === undefined) {
    const pr = await call('/v1/pairing', { method: 'POST', headers: auth(spaceToken) });
    code = ((await pr.json()) as { code: string }).code;
  }
  const form = new URLSearchParams({ handle, code, decision: 'pair' });
  const posted = await call('/authorize', {
    method: 'POST', redirect: 'manual',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded', Cookie: cookies(page), Origin: ORIGIN },
    body: form,
  });
  const location = posted.headers.get('Location');
  if (posted.status !== 302 || !location) return { page: posted };
  const authCode = new URL(location).searchParams.get('code');
  if (!authCode) return { page: posted };
  const tok = await call('/oauth/token', {
    method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({
      grant_type: 'authorization_code', code: authCode, redirect_uri: redirect, client_id: client.client_id,
      code_verifier: verifier, resource: `${ORIGIN}/mcp`,
    }),
  });
  const t = (await tok.json()) as { access_token?: string };
  return { accessToken: t.access_token, page: posted };
}

let rpcId = 0;

/** One MCP JSON-RPC call over Streamable HTTP. */
export async function mcp(accessToken: string, method: string, params: unknown = {}): Promise<any> {
  const res = await call('/mcp', {
    method: 'POST',
    headers: {
      Authorization: `Bearer ${accessToken}`, 'Content-Type': 'application/json',
      Accept: 'application/json, text/event-stream', 'MCP-Protocol-Version': '2025-06-18',
    },
    body: JSON.stringify({ jsonrpc: '2.0', id: ++rpcId, method, params }),
  });
  if (res.status !== 200) return { httpStatus: res.status, body: await res.text() };
  return res.json();
}

/** tools/call → the parsed JSON text, or `{ error }` when the tool reported one. */
export async function tool(accessToken: string, name: string, args: unknown = {}): Promise<any> {
  const r = await mcp(accessToken, 'tools/call', { name, arguments: args });
  if (r.httpStatus) return { httpStatus: r.httpStatus, body: r.body };
  if (r.error) return { rpcError: r.error };
  const text = r.result.content[0].text as string;
  if (r.result.isError) return { error: text };
  return JSON.parse(text);
}
