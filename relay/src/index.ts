// MyHealth AI planning relay. Three doors:
//  - /mcp: the MCP server, behind OAuth; the OAuth sign-in page asks for the app's pairing code.
//  - /v1/*: the app's sync API, with the space token from registration.
//  - /authorize: the pairing page.
import { OAuthProvider, AuthorizationError, type AuthRequest } from '@cloudflare/workers-oauth-provider';
import { OpError } from './rules';
import { Space, type AckResult } from './space';
import type { Env } from './types';

export { Space };

const PAIR_TTL_SEC = 600;
const PAIR_ALPHABET = 'ABCDEFGHJKMNPQRSTUVWXYZ23456789';

/** The MCP endpoint: OAuth has checked the token; its props name the space. */
const mcpHandler = {
  async fetch(request: Request, env: Env, ctx: ExecutionContext & { props?: { spaceId?: string } }): Promise<Response> {
    const spaceId = ctx.props?.spaceId;
    if (!spaceId) return new Response('No space', { status: 403 });
    return env.SPACE.get(env.SPACE.idFromName(spaceId)).fetch(request);
  },
};

const appHandler = {
  async fetch(request: Request, env: Env): Promise<Response> {
    const url = new URL(request.url);
    try {
      if (url.pathname === '/authorize') return await authorize(request, env);
      if (url.pathname.startsWith('/v1/')) return await api(request, env, url);
      if (url.pathname === '/' && request.method === 'GET') {
        return text('MyHealth AI planning relay. Add /mcp as a custom connector in Claude or ChatGPT.');
      }
      return text('Not found', 404);
    } catch (e) {
      if (e instanceof OpError) return json({ error: e.message }, 400);
      throw e;
    }
  },
};

/** One provider per origin the relay is reached at: the workers.dev URL, a custom domain, or `wrangler dev`. */
const providers = new Map<string, OAuthProvider<Env>>();

function providerFor(origin: string): OAuthProvider<Env> {
  let p = providers.get(origin);
  if (!p) {
    p = new OAuthProvider<Env>({
      apiRoute: '/mcp',
      apiHandler: mcpHandler as never,
      defaultHandler: appHandler as never,
      authorizeEndpoint: '/authorize',
      tokenEndpoint: '/oauth/token',
      clientRegistrationEndpoint: '/oauth/register',
      scopesSupported: ['mcp'],
      requiredScopes: ['mcp'],
      resourceMetadata: { resource: `${origin}/mcp`, authorization_servers: [origin] },
      // An hour per access token, renewed while in use; a month without use means pairing again.
      accessTokenTTL: 3600,
      refreshTokenTTL: 30 * 86_400,
    });
    providers.set(origin, p);
  }
  return p;
}

export default {
  fetch(request: Request, env: Env, ctx: ExecutionContext): Promise<Response> {
    return providerFor(new URL(request.url).origin).fetch(request, env, ctx);
  },
} satisfies ExportedHandler<Env>;

// --- the app's API --------------------------------------------------------------------------------

async function api(request: Request, env: Env, url: URL): Promise<Response> {
  const route = `${request.method} ${url.pathname}`;
  if (route === 'POST /v1/space') {
    if (!env.REGISTRATION_KEY || !safeEqual(bearer(request) ?? '', env.REGISTRATION_KEY)) return json({ error: 'Wrong registration key.' }, 401);
    const spaceId = crypto.randomUUID();
    const secret = randomToken(32);
    const ok = await stub(env, spaceId).register(await sha256(secret));
    if (!ok) return json({ error: 'Try again.' }, 409);
    return json({ spaceId, spaceToken: `${spaceId}.${secret}` });
  }

  const auth = await authenticate(request, env);
  if (!auth) return json({ error: 'Unknown or disconnected space.' }, 401);
  const { spaceId, space } = auth;

  switch (route) {
    case 'GET /v1/status': {
      const grants = await env.OAUTH_PROVIDER.listUserGrants(spaceId);
      const clients = await Promise.all(grants.items.map(async (g) => (await env.OAUTH_PROVIDER.lookupClient(g.clientId))?.clientName ?? 'AI app'));
      return json({ ...(await space.status()), connectors: clients });
    }
    case 'PUT /v1/snapshot': {
      const body = await request.text();
      const result = await space.putSnapshot(body);
      return json(result, 'error' in result ? 400 : 200);
    }
    case 'GET /v1/ops':
      return json({ ops: await space.pullOps() });
    case 'POST /v1/ack': {
      const body = (await request.json()) as { results?: AckResult[] };
      return json({ acked: await space.ack(body.results ?? []) });
    }
    case 'POST /v1/pairing': {
      const code = randomCode(8);
      await env.OAUTH_KV.put(pairKey(code), spaceId, { expirationTtl: PAIR_TTL_SEC });
      return json({ code, expiresInSec: PAIR_TTL_SEC });
    }
    case 'DELETE /v1/space': {
      let cursor: string | undefined;
      do {
        const page = await env.OAUTH_PROVIDER.listUserGrants(spaceId, { cursor });
        for (const g of page.items) await env.OAUTH_PROVIDER.revokeGrant(g.id, spaceId);
        cursor = page.cursor;
      } while (cursor);
      await space.wipe();
      return json({ deleted: true });
    }
  }
  return json({ error: 'Not found' }, 404);
}

async function authenticate(request: Request, env: Env) {
  const token = bearer(request);
  const dot = token?.indexOf('.') ?? -1;
  if (!token || dot < 1) return undefined;
  const spaceId = token.slice(0, dot);
  if (!/^[0-9a-f-]{36}$/.test(spaceId)) return undefined;
  const space = stub(env, spaceId);
  return (await space.verify(await sha256(token.slice(dot + 1)))) ? { spaceId, space } : undefined;
}

const stub = (env: Env, spaceId: string) => env.SPACE.get(env.SPACE.idFromName(spaceId));

// --- pairing (the OAuth sign-in page) --------------------------------------------------------------

async function authorize(request: Request, env: Env): Promise<Response> {
  const oauth = env.OAUTH_PROVIDER;
  try {
    if (request.method === 'GET') {
      const authRequest = await oauth.parseAuthRequest(request);
      const details = await oauth.describeConsent(authRequest);
      const consent = await oauth.beginConsent(authRequest);
      consent.headers.set('Content-Type', 'text/html; charset=utf-8');
      return new Response(pairingPage(details, consent.handle), { headers: consent.headers });
    }
    if (request.method === 'POST') {
      const form = await request.formData();
      const handle = String(form.get('handle') ?? '');
      if (form.get('decision') !== 'pair') {
        const denied = await oauth.denyConsent(request, handle);
        return new Response(null, { status: 302, headers: denied.headers });
      }
      const code = String(form.get('code') ?? '').toUpperCase().replace(/[^A-Z0-9]/g, '');
      const spaceId = code.length === 8 ? await env.OAUTH_KV.get(pairKey(code)) : null;
      if (!spaceId) {
        return html(errorPage('That pairing code is wrong or has expired. In MyHealth › More › AI planning, tap "Pair Claude / ChatGPT" for a new code, then add the connector again.'), 400);
      }
      await env.OAUTH_KV.delete(pairKey(code));
      const approved = await oauth.approveConsent(request, handle, { scope: ['mcp'] });
      const { redirectTo } = await oauth.completeAuthorization({
        request: approved.request as AuthRequest,
        userId: spaceId,
        metadata: { pairedAt: Date.now() },
        scope: ['mcp'],
        props: { spaceId },
        revokeExistingGrants: true,
      });
      approved.headers.set('Location', redirectTo);
      return new Response(null, { status: 302, headers: approved.headers });
    }
    return text('Method not allowed', 405);
  } catch (e) {
    if (e instanceof AuthorizationError && e.redirectUri) {
      const to = new URL(e.redirectUri);
      to.searchParams.set('error', e.code);
      to.searchParams.set('error_description', e.description);
      if (e.state) to.searchParams.set('state', e.state);
      if (e.issuer) to.searchParams.set('iss', e.issuer);
      return Response.redirect(to.toString(), 302);
    }
    if (e instanceof AuthorizationError) return html(errorPage(e.description), 400);
    if (e instanceof Error && e.name === 'CimdFetchError') return html(errorPage('This app could not be verified.'), 400);
    throw e;
  }
}

const escape = (value: string) => value.replace(/[&<>"']/g, (c) => `&#${c.charCodeAt(0)};`);

const PAGE_STYLE = `body{font:16px/1.5 system-ui,sans-serif;max-width:30rem;margin:2rem auto;padding:0 1rem;color:#16241c;background:#f4f8f5}
h1{font-size:1.4rem;color:#1d6b3f}input{font:1.4rem ui-monospace,monospace;letter-spacing:.2em;width:100%;padding:.5rem;box-sizing:border-box;text-transform:uppercase}
button{font-size:1rem;padding:.6rem 1.2rem;margin:.8rem .5rem 0 0;border-radius:.5rem;border:1px solid #1d6b3f}
.go{background:#1d6b3f;color:#fff}.note{color:#4a5d51;font-size:.9rem}
@media (prefers-color-scheme:dark){body{background:#0f1712;color:#e3efe7}h1{color:#7fd3a0}.note{color:#9fb5a7}}`;

function pairingPage(details: { clientName: string; clientDomain?: string; redirectHost: string; redirectIsLoopback?: boolean }, handle: string): string {
  const name = escape(details.clientName || 'An AI app');
  const origin = details.clientDomain ? `Published by <strong>${escape(details.clientDomain)}</strong>.` : 'This app named itself; its name is not verified.';
  return `<!doctype html><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Pair MyHealth</title><style>${PAGE_STYLE}</style>
<h1>Connect ${name} to MyHealth</h1>
<p>${name} will read your training data that MyHealth shares and plan sessions, workouts and exercises in the app.</p>
<p class="note">${origin} Access goes to <strong>${escape(details.redirectHost)}</strong>.</p>
${details.redirectIsLoopback ? '<p><strong>This sends access to an app on this computer.</strong> Continue only if you just started connecting from it.</p>' : ''}
<form method="post">
<input type="hidden" name="handle" value="${escape(handle)}">
<label for="code">Pairing code from MyHealth › More › AI planning</label>
<input id="code" name="code" autocomplete="one-time-code" maxlength="9" required autofocus>
<button class="go" name="decision" value="pair">Connect</button><button name="decision" value="deny" formnovalidate>Cancel</button>
</form>`;
}

function errorPage(message: string): string {
  return `<!doctype html><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Pair MyHealth</title><style>${PAGE_STYLE}</style><h1>Not connected</h1><p>${escape(message)}</p>`;
}

// --- helpers ----------------------------------------------------------------------------------------

const pairKey = (code: string) => `pair:${code}`;

function bearer(request: Request): string | undefined {
  const h = request.headers.get('Authorization');
  return h?.startsWith('Bearer ') ? h.slice(7).trim() : undefined;
}

function randomToken(bytes: number): string {
  const b = crypto.getRandomValues(new Uint8Array(bytes));
  return btoa(String.fromCharCode(...b)).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

function randomCode(length: number): string {
  const b = crypto.getRandomValues(new Uint8Array(length));
  return Array.from(b, (x) => PAIR_ALPHABET[x % PAIR_ALPHABET.length]).join('');
}

async function sha256(s: string): Promise<string> {
  const d = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(s));
  return Array.from(new Uint8Array(d), (x) => x.toString(16).padStart(2, '0')).join('');
}

function safeEqual(a: string, b: string): boolean {
  if (a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i++) diff |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return diff === 0;
}

const json = (value: unknown, status = 200) => Response.json(value, { status, headers: { 'Cache-Control': 'no-store' } });
const text = (value: string, status = 200) => new Response(value, { status, headers: { 'Content-Type': 'text/plain; charset=utf-8' } });
const html = (value: string, status = 200) =>
  new Response(value, { status, headers: { 'Content-Type': 'text/html; charset=utf-8', 'X-Frame-Options': 'DENY' } });
