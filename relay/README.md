# MyHealth AI planning relay

The small server that lets Claude or ChatGPT plan training in the MyHealth app (P28). The app itself stays on the
phone; this relay sits in between:

```
Claude / ChatGPT ──MCP + OAuth──▶ relay (your Cloudflare account) ◀──sync── MyHealth app
```

- The **app** pushes a snapshot of the training data its owner chose to share (exercises, equipment, workouts, goals,
  planned sessions, calendar, and optionally load, body and cycle). It pulls the AI's changes, checks them again,
  and applies them, with Undo.
- The **AI app** connects to `/mcp` as a custom connector. Its sign-in page asks for the 8-character pairing code
  the app shows, which binds that connector to that phone.
- Each phone has its own **space** (a SQLite Durable Object). Spaces never see each other. Disconnect in the app
  deletes the space and revokes the connector.

It runs on the Cloudflare Workers **free plan**: 100,000 requests a day, which is far more than a family uses.

## Deploy (once, for the whole family)

You need a free Cloudflare account and Node.js 20 or newer.

```sh
cd relay
npm install
npx wrangler login          # opens the browser
npx wrangler deploy         # prints https://myhealth-relay.<your-subdomain>.workers.dev
node scripts/setup.mjs https://myhealth-relay.<your-subdomain>.workers.dev
```

`setup.mjs` creates a random registration key, stores it as the Worker secret `REGISTRATION_KEY`, and prints the
**connection code** (`myhealth-ai:1:…`). Paste that code into each phone's MyHealth › More › AI planning.

Keep the relay URL, the key and the connection code private: anyone with the code can register a phone on your relay
(they still can't read other spaces). To lock everyone out, run `setup.mjs` again; phones that already registered
keep working, new ones need the new code.

## Connect Claude or ChatGPT

1. In MyHealth › More › AI planning, tap **Pair Claude / ChatGPT**. The app shows a code that's valid for 10 minutes.
2. **Claude** (web or desktop): Settings › Connectors › Add custom connector. URL: `https://<relay>/mcp`. Then
   Connect, and type the code on the page that opens. The connector then also works in the Claude mobile app.
   **ChatGPT** (paid plans): turn on developer mode in the settings, then add a custom connector (app) with the same
   URL. Write tools only work in developer mode.
3. Ask, e.g. "Plan my next two weeks around my matches. I have dumbbells and a band."

## Develop

```sh
npm test                    # vitest in the Workers runtime (OAuth pairing, tools, isolation, limits)
npx tsc --noEmit
../tools/relay-dev.sh       # wrangler dev on :8787; the Android emulator reaches it at http://10.0.2.2:8787
```

`test/fixtures/snapshot.json` is written by the Kotlin test `AiRelayFixture` (`AI_FIXTURE_OUT=1`). It holds made-up
data in the exact format the phone sends.

The phone is the authority: `src/rules.ts` mirrors the Kotlin `AiOpPlanner` only so that the AI gets an answer at
once. Field values come from the snapshot's `vocab`, so the app's enums are never copied here.
