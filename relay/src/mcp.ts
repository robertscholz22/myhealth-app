// The MCP server Claude / ChatGPT talk to: read tools over the phone's snapshot, write tools that queue
// changes for the phone. Stateless Streamable HTTP with JSON responses, one server per request.
import { McpServer } from '@modelcontextprotocol/sdk/server/mcp.js';
import { WebStandardStreamableHTTPServerTransport } from '@modelcontextprotocol/sdk/server/webStandardStreamableHttp.js';
import { z } from 'zod';
import { OpError, resolve, type View } from './rules';
import type { Space } from './space';
import type { Op, SessionDto, SessionFields, WorkoutRow } from './types';

export const SERVER_NAME = 'MyHealth training planner';

const INSTRUCTIONS = `You are planning training in the owner's MyHealth app (Android / iPhone).
- Start with get_overview, then get_calendar for the weeks you plan.
- The app measures load in AU (session RPE x minutes x 0.3, close to TRIMP). Fitness = 42-day average load (CTL),
  fatigue = 7-day (ATL); ACWR = acute:chronic ratio, keep it about 0.8-1.3 and raise weekly load by at most ~10%.
- Calendar events (matches, team training, imported calendars) and locked or done sessions are fixed: plan around them.
- You may create exercises, workouts and planned sessions; change or delete workouts you created and planned sessions
  marked changeable. Never touch the owner's own exercises or workouts: create new ones instead.
- Changes are applied on the phone when the app syncs (on open, and in the background); until then they show as
  pending. get_sync_status shows what the phone applied or refused, with the reason.
- Give each session a short rationale: the owner sees it in the app.`;

type Text = { content: { type: 'text'; text: string }[]; isError?: boolean };

const ok = (value: unknown): Text => ({ content: [{ type: 'text', text: JSON.stringify(value) }] });
const err = (message: string): Text => ({ content: [{ type: 'text', text: message }], isError: true });

export async function handleMcp(request: Request, space: Space): Promise<Response> {
  const server = buildServer(space);
  const transport = new WebStandardStreamableHTTPServerTransport({ sessionIdGenerator: undefined, enableJsonResponse: true });
  await server.connect(transport);
  return transport.handleRequest(request);
}

const newAiId = () => 'ai-' + crypto.randomUUID().replace(/-/g, '').slice(0, 10);
const newOpId = () => crypto.randomUUID();

const READ = { readOnlyHint: true, openWorldHint: false } as const;
const WRITE = { readOnlyHint: false, destructiveHint: false, openWorldHint: false } as const;
const DELETE = { readOnlyHint: false, destructiveHint: true, openWorldHint: false } as const;

const rowSchema = z.object({
  exerciseId: z.string().describe('Exercise id from list_exercises, or the ai-… id of one you created'),
  sets: z.number().int(),
  reps: z.number().int().optional().describe('Reps per set (not for timed exercises)'),
  seconds: z.number().int().optional().describe('Seconds per set, for timed exercises'),
  loadKg: z.number().optional(),
  restSec: z.number().int().optional(),
  note: z.string().optional(),
});

const sessionShape = {
  day: z.string().optional().describe('YYYY-MM-DD'),
  start: z.string().optional().describe('HH:MM, or "" for no time'),
  sport: z.string().optional().describe('One of the sports in get_overview.vocab.sports'),
  sessionType: z.string().optional().describe('One of the session types for that sport; default is the first'),
  intensity: z.string().optional().describe('RECOVERY, LOW, MODERATE, HIGH or MAX'),
  durationMin: z.number().int().optional(),
  distanceKm: z.number().optional(),
  pace: z.string().optional().describe('Target pace m:ss per km'),
  workout: z.string().optional().describe('Workout id (w12 or ai-…) for a strength or mobility session; "" for none'),
  description: z.string().optional().describe('What to do, e.g. "6 x 3 min at 10k pace, 2 min jog"'),
  rationale: z.string().optional().describe('Why this session, shown to the owner'),
};

function buildServer(space: Space): McpServer {
  const server = new McpServer({ name: SERVER_NAME, version: '1.0.0' }, { instructions: INSTRUCTIONS });

  const withView = (fn: (view: View) => unknown): Text => {
    const view = space.view();
    if (!view) return err('The phone has not synced yet. Ask the owner to open MyHealth › More › AI planning.');
    try {
      return ok(fn(view));
    } catch (e) {
      if (e instanceof OpError) return err(e.message);
      throw e;
    }
  };

  const submit = (ops: { op: Op; summary: string }[], result: unknown): Text => {
    try {
      space.submit(ops);
      return ok({ ...(result as object), status: 'pending', note: 'Applied on the phone at its next sync.' });
    } catch (e) {
      if (e instanceof OpError) return err(e.message);
      throw e;
    }
  };

  // --- overview ---------------------------------------------------------------------------------

  server.registerTool(
    'get_overview',
    {
      title: 'Overview',
      description: 'The owner\'s planning context: today on the phone, goals, sports, equipment, the planning window, data groups shared, when the phone last synced, and the values every field accepts (vocab).',
      annotations: READ,
    },
    () => withView((v) => {
      const s = v.snapshot;
      const status = space.status();
      return {
        today: s.today,
        phoneSyncedAt: status.snapshotAt ? new Date(status.snapshotAt).toISOString() : null,
        pendingChanges: status.pending,
        dataGroups: s.groups,
        window: s.window,
        goals: s.goals,
        sports: s.sports,
        equipment: v.equipment,
        counts: {
          exercises: v.exercises.length,
          workouts: v.workouts.length,
          sessionsAhead: v.sessions.filter((x) => x.day >= s.today).length,
          eventsAhead: s.events.filter((x) => x.day >= s.today).length,
        },
        vocab: s.vocab,
      };
    }),
  );

  // --- exercises and equipment ------------------------------------------------------------------

  server.registerTool(
    'list_exercises',
    {
      title: 'List exercises',
      description: 'Exercises the owner can use: the app catalog, the owner\'s own ("mine") and ones you created ("ai"). Filter by text, muscle, equipment, pattern or source.',
      inputSchema: {
        query: z.string().optional(),
        muscle: z.string().optional(),
        equipment: z.string().optional(),
        pattern: z.string().optional(),
        source: z.enum(['catalog', 'mine', 'ai']).optional(),
        onlyMyEquipment: z.boolean().optional().describe('Only exercises the owner\'s equipment allows'),
        limit: z.number().int().optional(),
      },
      annotations: READ,
    },
    (a) => withView((v) => {
      const q = a.query?.toLowerCase().trim();
      const up = (s?: string) => s?.trim().toUpperCase().replace(/[ -]/g, '_');
      const rows = v.exercises.filter((e) =>
        (!q || e.name.toLowerCase().includes(q) || e.id.toLowerCase().includes(q)) &&
        (!a.muscle || e.primary.includes(up(a.muscle)!) || e.secondary.includes(up(a.muscle)!)) &&
        (!a.equipment || e.equipment === up(a.equipment)) &&
        (!a.pattern || e.pattern === up(a.pattern)) &&
        (!a.source || e.source === a.source) &&
        (!a.onlyMyEquipment || v.equipment.includes(e.equipment)));
      const limit = Math.min(Math.max(a.limit ?? 100, 1), 300);
      return { total: rows.length, exercises: rows.slice(0, limit) };
    }),
  );

  server.registerTool(
    'get_exercise',
    { title: 'Get exercise', description: 'One exercise by id.', inputSchema: { id: z.string() }, annotations: READ },
    (a) => withView((v) => v.exercises.find((e) => e.id === resolve(v, a.id)) ?? `No exercise ${a.id}.`),
  );

  server.registerTool(
    'create_exercise',
    {
      title: 'Create exercise',
      description: 'Adds an exercise to the owner\'s own list, for workouts. Check list_exercises first: an exercise with the same name is refused. Returns its ai-… id, usable in workouts right away.',
      inputSchema: {
        name: z.string(),
        primary: z.array(z.string()).describe('Primary muscles (vocab.muscles)'),
        secondary: z.array(z.string()).optional(),
        equipment: z.string().optional().describe('vocab.equipment; default BODYWEIGHT'),
        pattern: z.string().optional().describe('vocab.patterns; default OTHER'),
        timed: z.boolean().optional().describe('Done for seconds instead of reps (planks, holds)'),
        oneSided: z.boolean().optional(),
        notes: z.string().optional().describe('How to do it'),
      },
      annotations: WRITE,
    },
    (a) => {
      const aiId = newAiId();
      return submit([{ op: { type: 'create_exercise', opId: newOpId(), aiId, ...a }, summary: `Create exercise ${a.name}` }], { id: aiId });
    },
  );

  server.registerTool(
    'get_equipment',
    { title: 'My equipment', description: 'The equipment the owner has, and every value the app knows.', annotations: READ },
    () => withView((v) => ({ equipment: v.equipment, allOptions: v.snapshot.vocab.equipment })),
  );

  server.registerTool(
    'set_equipment',
    {
      title: 'Set my equipment',
      description: 'Replaces the owner\'s equipment list (bodyweight is always included). Only when the owner told you what they have.',
      inputSchema: { equipment: z.array(z.string()) },
      annotations: WRITE,
    },
    (a) => submit([{ op: { type: 'set_equipment', opId: newOpId(), equipment: a.equipment }, summary: `Set equipment: ${a.equipment.join(', ')}` }], {}),
  );

  // --- workouts ---------------------------------------------------------------------------------

  server.registerTool(
    'list_workouts',
    { title: 'List workouts', description: 'Strength, mobility and video workouts, with estimated minutes. byAi = you created it and may change it.', annotations: READ },
    () => withView((v) => v.workouts.map(({ rows, ...w }) => ({ ...w, exercises: rows.length }))),
  );

  server.registerTool(
    'get_workout',
    { title: 'Get workout', description: 'One workout with its exercises, sets and reps.', inputSchema: { id: z.string() }, annotations: READ },
    (a) => withView((v) => {
      const w = v.workouts.find((x) => x.id === resolve(v, a.id));
      if (!w) return `No workout ${a.id}.`;
      return { ...w, rows: w.rows.map((r) => ({ ...r, name: v.exercises.find((e) => e.id === resolve(v, r.exerciseId))?.name })) };
    }),
  );

  server.registerTool(
    'create_workout',
    {
      title: 'Create workout',
      description: 'A new strength or mobility workout from exercises (ids from list_exercises). Plan it on a day with schedule_sessions (workout = the returned id).',
      inputSchema: {
        name: z.string(),
        kind: z.string().optional().describe('vocab.workoutKinds: FULL, UPPER, LOWER, CORE, CUSTOM, MOBILITY_…'),
        rows: z.array(rowSchema),
        notes: z.string().optional(),
      },
      annotations: WRITE,
    },
    (a) => {
      const aiId = newAiId();
      return submit([{ op: { type: 'create_workout', opId: newOpId(), aiId, name: a.name, kind: a.kind, rows: a.rows as WorkoutRow[], notes: a.notes }, summary: `Create workout ${a.name}` }], { id: aiId });
    },
  );

  server.registerTool(
    'update_workout',
    {
      title: 'Update workout',
      description: 'Changes a workout you created (byAi). Fields left out stay; rows, when given, replace all rows.',
      inputSchema: { id: z.string(), name: z.string().optional(), kind: z.string().optional(), rows: z.array(rowSchema).optional(), notes: z.string().optional() },
      annotations: WRITE,
    },
    (a) => submit([{ op: { type: 'update_workout', opId: newOpId(), ref: a.id, name: a.name, kind: a.kind, rows: a.rows as WorkoutRow[] | undefined, notes: a.notes }, summary: `Update workout ${a.id}` }], { id: a.id }),
  );

  server.registerTool(
    'delete_workout',
    { title: 'Delete workout', description: 'Deletes a workout you created that no session uses.', inputSchema: { id: z.string() }, annotations: DELETE },
    (a) => submit([{ op: { type: 'delete_workout', opId: newOpId(), ref: a.id }, summary: `Delete workout ${a.id}` }], { id: a.id }),
  );

  // --- calendar ---------------------------------------------------------------------------------

  server.registerTool(
    'get_calendar',
    {
      title: 'Calendar',
      description: 'Planned sessions (locked / changeable / done), fixed calendar events and done activities between two days (default: today and the next 14 days). The phone shares 4 weeks back to 12 weeks ahead.',
      inputSchema: { from: z.string().optional().describe('YYYY-MM-DD'), to: z.string().optional().describe('YYYY-MM-DD') },
      annotations: READ,
    },
    (a) => withView((v) => {
      const s = v.snapshot;
      const from = a.from ?? s.today;
      const to = a.to ?? addDays(s.today, 14);
      const inRange = (d: string) => d >= from && d <= to;
      const workoutName = (id?: string) => (id ? v.workouts.find((w) => w.id === id)?.name : undefined);
      return {
        from, to,
        sessions: v.sessions.filter((x) => inRange(x.day)).sort(byDay).map((x) => ({ ...x, workoutName: workoutName(x.workout) })),
        events: s.events.filter((x) => inRange(x.day)),
        activities: s.activities.filter((x) => inRange(x.day)),
      };
    }),
  );

  server.registerTool(
    'schedule_sessions',
    {
      title: 'Schedule sessions',
      description: 'Adds planned sessions (all or none). Each needs day and sport (or a workout). Returns their ai-… ids.',
      inputSchema: { sessions: z.array(z.object(sessionShape)).min(1).max(60) },
      annotations: WRITE,
    },
    (a) => {
      const items = a.sessions.map((f) => ({ aiId: newAiId(), f: f as SessionFields }));
      return submit(
        items.map(({ aiId, f }) => ({ op: { type: 'schedule_session', opId: newOpId(), aiId, session: f }, summary: `Schedule ${f.sessionType ?? f.sport ?? 'session'} on ${f.day}` })),
        { ids: items.map((i) => i.aiId) },
      );
    },
  );

  server.registerTool(
    'update_session',
    {
      title: 'Update session',
      description: 'Changes a planned session marked changeable (not locked, not done, not past). Fields left out stay.',
      inputSchema: { id: z.string().describe('s34 or ai-…'), ...sessionShape },
      annotations: WRITE,
    },
    ({ id, ...f }) => submit([{ op: { type: 'update_session', opId: newOpId(), ref: id, session: f as SessionFields }, summary: `Update session ${id}` }], { id }),
  );

  server.registerTool(
    'delete_session',
    { title: 'Delete session', description: 'Removes a planned session marked changeable.', inputSchema: { id: z.string() }, annotations: DELETE },
    (a) => submit([{ op: { type: 'delete_session', opId: newOpId(), ref: a.id }, summary: `Delete session ${a.id}` }], { id: a.id }),
  );

  // --- status -----------------------------------------------------------------------------------

  server.registerTool(
    'get_training_status',
    {
      title: 'Training status',
      description: 'Load (daily load, fitness, fatigue, ACWR, recovery) and strength progress, body (age, HR, sleep, readiness) and cycle phase — whichever groups the owner shares.',
      annotations: READ,
    },
    () => withView((v) => {
      const s = v.snapshot;
      const off = 'Not shared: the owner turned this group off in the app.';
      return { load: s.load ?? off, body: s.body ?? off, cycle: s.cycle ?? off };
    }),
  );

  server.registerTool(
    'get_sync_status',
    {
      title: 'Sync status',
      description: 'When the phone last synced, and your recent changes: pending (not on the phone yet), applied, or rejected with the reason.',
      annotations: READ,
    },
    () => {
      const st = space.status();
      return ok({
        phoneSyncedAt: st.snapshotAt ? new Date(st.snapshotAt).toISOString() : null,
        pending: st.pending,
        recent: space.recentOps(30).map((r) => ({ summary: r.summary, status: r.status, reason: r.reason, at: new Date(r.createdAt).toISOString() })),
      });
    },
  );

  return server;
}

function addDays(day: string, n: number): string {
  return new Date(Date.parse(day + 'T00:00:00Z') + n * 86_400_000).toISOString().slice(0, 10);
}

const byDay = (a: SessionDto, b: SessionDto) => (a.day + (a.start ?? '')).localeCompare(b.day + (b.start ?? ''));
