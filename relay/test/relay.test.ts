import { describe, expect, it } from 'vitest';
import { auth, call, mcp, pairConnector, putSnapshot, register, REG_KEY, snapshot, tool } from './helpers';

async function connected() {
  const space = await register();
  expect((await putSnapshot(space.spaceToken)).status).toBe(200);
  const { accessToken } = await pairConnector(space.spaceToken);
  expect(accessToken).toBeTruthy();
  return { ...space, accessToken: accessToken! };
}

async function pull(token: string) {
  const res = await call('/v1/ops', { headers: auth(token) });
  return ((await res.json()) as { ops: { opId: string; op: any }[] }).ops;
}

async function ack(token: string, results: unknown[]) {
  return call('/v1/ack', { method: 'POST', headers: { ...auth(token), 'Content-Type': 'application/json' }, body: JSON.stringify({ results }) });
}

describe('registration and the app API', () => {
  it('needs the registration key', async () => {
    expect((await call('/v1/space', { method: 'POST' })).status).toBe(401);
    expect((await call('/v1/space', { method: 'POST', headers: auth('wrong-key-wrong-key-wrong-key-00') })).status).toBe(401);
    const space = await register();
    expect(space.spaceToken.startsWith(space.spaceId + '.')).toBe(true);
  });

  it('refuses unknown tokens and a token for another space', async () => {
    const a = await register();
    const b = await register();
    expect((await call('/v1/status', { headers: auth('nope') })).status).toBe(401);
    const forged = `${b.spaceId}.${a.spaceToken.split('.')[1]}`;
    expect((await call('/v1/status', { headers: auth(forged) })).status).toBe(401);
    expect((await call('/v1/status', { headers: auth(a.spaceToken) })).status).toBe(200);
    expect((await call('/v1/status', { headers: auth(REG_KEY) })).status).toBe(401);
  });

  it('stores a snapshot and reports status', async () => {
    const s = await register();
    expect((await putSnapshot(s.spaceToken)).status).toBe(200);
    const st = (await (await call('/v1/status', { headers: auth(s.spaceToken) })).json()) as any;
    expect(st.today).toBe('2026-12-01');
    expect(st.groups).toEqual(['BASICS', 'LOAD', 'BODY', 'CYCLE']);
    expect(st.pending).toBe(0);
    expect(st.connectors).toEqual([]);
  });

  it('refuses a snapshot over 1 MB and one that is not a snapshot', async () => {
    const s = await register();
    const big = { ...snapshot, padding: 'x'.repeat(1_000_001) };
    expect((await putSnapshot(s.spaceToken, big)).status).toBe(400);
    expect((await putSnapshot(s.spaceToken, { hello: 1 })).status).toBe(400);
  });
});

describe('pairing a connector', () => {
  it('pairs with the code from the app and lists the connector', async () => {
    const s = await connected();
    const st = (await (await call('/v1/status', { headers: auth(s.spaceToken) })).json()) as any;
    expect(st.connectors).toEqual(['Claude']);
  });

  it('refuses a wrong code, and a code works once', async () => {
    const s = await register();
    const bad = await pairConnector(s.spaceToken, { code: 'ZZZZZZZZ' });
    expect(bad.accessToken).toBeUndefined();
    expect(bad.page.status).toBe(400);
    expect(await bad.page.text()).toContain('wrong or has expired');

    const pr = await call('/v1/pairing', { method: 'POST', headers: auth(s.spaceToken) });
    const { code } = (await pr.json()) as { code: string };
    expect(code).toMatch(/^[A-HJ-NP-Z2-9]{8}$/);
    expect((await pairConnector(s.spaceToken, { code })).accessToken).toBeTruthy();
    expect((await pairConnector(s.spaceToken, { code })).accessToken).toBeUndefined();
  });

  it('the MCP endpoint needs a token', async () => {
    const res = await call('/mcp', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: '{}' });
    expect(res.status).toBe(401);
    expect(res.headers.get('WWW-Authenticate')).toContain('resource_metadata');
  });
});

describe('MCP tools', () => {
  it('initializes and lists the tools with hints', async () => {
    const s = await connected();
    const init = await mcp(s.accessToken, 'initialize', { protocolVersion: '2025-06-18', capabilities: {}, clientInfo: { name: 't', version: '1' } });
    expect(init.result.serverInfo.name).toBe('MyHealth training planner');
    expect(init.result.instructions).toContain('locked');
    const list = await mcp(s.accessToken, 'tools/list');
    const names = list.result.tools.map((t: any) => t.name);
    expect(names).toEqual(expect.arrayContaining([
      'get_overview', 'list_exercises', 'get_exercise', 'create_exercise', 'get_equipment', 'set_equipment',
      'update_exercise', 'list_workouts', 'get_workout', 'create_workout', 'update_workout', 'delete_workout', 'get_calendar',
      'schedule_sessions', 'update_session', 'delete_session', 'get_training_status', 'get_sync_status',
    ]));
    const hints = Object.fromEntries(list.result.tools.map((t: any) => [t.name, t.annotations]));
    expect(hints.get_calendar.readOnlyHint).toBe(true);
    expect(hints.delete_session.destructiveHint).toBe(true);
  });

  it('reads the overview, exercises, workouts and calendar', async () => {
    const s = await connected();
    const o = await tool(s.accessToken, 'get_overview');
    expect(o.today).toBe('2026-12-01');
    expect(o.equipment).toEqual(['BODYWEIGHT', 'DUMBBELL', 'BAND']);
    expect(o.vocab.sports.CYCLING).toContain('ENDURANCE_RIDE');

    const ex = await tool(s.accessToken, 'list_exercises', { muscle: 'lats', source: 'mine' });
    expect(ex.exercises.map((e: any) => e.name)).toEqual(['Towel row']);
    const db = await tool(s.accessToken, 'list_exercises', { equipment: 'barbell', onlyMyEquipment: true });
    expect(db.total).toBe(0);

    const w = await tool(s.accessToken, 'get_workout', { id: 'w1' });
    expect(w.rows.map((r: any) => r.name)).toEqual(['Goblet squat', 'Push-up', 'Plank']);

    const cal = await tool(s.accessToken, 'get_calendar', {});
    expect(cal.sessions.map((x: any) => `${x.id}:${x.changeable}`)).toEqual(['s10:true', 's12:true', 's11:false']);
    expect(cal.sessions.find((x: any) => x.id === 's12').workoutName).toBe('My full body');
    expect(cal.events[0].title).toBe('Team training');

    const ts = await tool(s.accessToken, 'get_training_status');
    expect(ts.cycle.phase).toBe('FOLLICULAR');
  });

  it('plans a week: exercise → workout → sessions, visible as pending, queued for the phone', async () => {
    const s = await connected();
    const ex = await tool(s.accessToken, 'create_exercise', { name: 'Banded face pull', primary: ['SHOULDERS_REAR'], equipment: 'band', pattern: 'HORIZONTAL_PULL' });
    expect(ex.id).toMatch(/^ai-/);
    expect(ex.status).toBe('pending');
    const w = await tool(s.accessToken, 'create_workout', {
      name: 'Home pull A', kind: 'UPPER',
      rows: [{ exerciseId: ex.id, sets: 3, reps: 15 }, { exerciseId: 'USER_TOWELROW01', sets: 3, reps: 10 }, { exerciseId: 'PLANK', sets: 2, seconds: 45 }],
    });
    expect(w.id).toMatch(/^ai-/);
    const sched = await tool(s.accessToken, 'schedule_sessions', {
      sessions: [
        { day: '2026-12-02', workout: w.id, rationale: 'Pull balance' },
        { day: '2026-12-05', sport: 'RUN_OUTDOOR', sessionType: 'TEMPO_RUN', durationMin: 40, pace: '5:10', description: '3 x 8 min tempo' },
      ],
    });
    expect(sched.ids).toHaveLength(2);

    const cal = await tool(s.accessToken, 'get_calendar', { from: '2026-12-01', to: '2026-12-07' });
    const planned = cal.sessions.find((x: any) => x.id === sched.ids[0]);
    expect(planned).toMatchObject({ sessionType: 'STRENGTH_UPPER', sport: 'STRENGTH', pending: true, workoutName: 'Home pull A', byAi: true });
    expect(cal.sessions.find((x: any) => x.id === sched.ids[1]).intensity).toBe('HIGH');

    const ops = await pull(s.spaceToken);
    expect(ops.map((o) => o.op.type)).toEqual(['create_exercise', 'create_workout', 'schedule_session', 'schedule_session']);
    expect(ops[2].op).toMatchObject({ aiId: sched.ids[0], session: { day: '2026-12-02', workout: w.id } });
    const sync = await tool(s.accessToken, 'get_sync_status');
    expect(sync.pending).toBe(4);
  });

  it('creates a video workout from a training video and checks its link', async () => {
    const s = await connected();
    const w = await tool(s.accessToken, 'create_workout', {
      name: 'Glutes 20', kind: 'LOWER',
      rows: [{ exerciseId: 'PLANK', sets: 2, seconds: 40, sourceLine: '3:10 Plank 40s' }],
      videoUrl: 'https://youtu.be/dQw4w9WgXcQ', videoTitle: '20 min glutes', videoMinutes: 21, sourceText: '3:10 Plank 40s',
    });
    expect(w.id).toMatch(/^ai-/);
    const got = await tool(s.accessToken, 'get_workout', { id: w.id });
    expect(got).toMatchObject({ video: true, videoUrl: 'https://youtu.be/dQw4w9WgXcQ', videoTitle: '20 min glutes', minutes: 21 });
    const [op] = await pull(s.spaceToken);
    expect(op.op).toMatchObject({ type: 'create_workout', videoUrl: 'https://youtu.be/dQw4w9WgXcQ', videoMinutes: 21, sourceText: '3:10 Plank 40s' });
    expect(op.op.rows[0].sourceLine).toBe('3:10 Plank 40s');

    const removed = await tool(s.accessToken, 'update_workout', { id: w.id, videoUrl: '' });
    expect(removed.error).toBeUndefined();
    const after = await tool(s.accessToken, 'get_workout', { id: w.id });
    expect(after.video).toBe(false);
    expect(after.minutes).not.toBe(21);

    const rows = [{ exerciseId: 'PLANK', sets: 1, seconds: 30 }];
    expect((await tool(s.accessToken, 'create_workout', { name: 'X', rows, videoUrl: 'http://youtu.be/dQw4w9WgXcQ' })).error).toContain('https');
    expect((await tool(s.accessToken, 'create_workout', { name: 'X', rows, videoTitle: 'T' })).error).toContain('need a videoUrl');
    expect((await tool(s.accessToken, 'create_workout', { name: 'X', rows, videoUrl: 'https://youtu.be/x', videoMinutes: 0 })).error).toContain('videoMinutes');
    const list = await mcp(s.accessToken, 'tools/list');
    expect(list.result.tools.find((t: any) => t.name === 'create_workout').description).toContain('videoUrl');
  });

  it('corrects its own and the owner\'s exercises, never built-in ones', async () => {
    const s = await connected();
    const towel = (await tool(s.accessToken, 'list_exercises', { source: 'mine' })).exercises[0];
    expect(towel).toMatchObject({ id: 'USER_TOWELROW01', byAi: false, editable: true });
    expect((await tool(s.accessToken, 'list_exercises', { query: 'push-up' })).exercises[0].editable).toBe(false);

    const ex = await tool(s.accessToken, 'create_exercise', { name: 'Deep lunge stretch', primary: ['GLUTES', 'QUADS'], pattern: 'MOBILITY', timed: true });
    const fixed = await tool(s.accessToken, 'update_exercise', { id: ex.id, primary: ['QUADS'], secondary: ['GLUTES'] });
    expect(fixed.error).toBeUndefined();
    expect(await tool(s.accessToken, 'get_exercise', { id: ex.id })).toMatchObject({ primary: ['QUADS'], secondary: ['GLUTES'], byAi: true, source: 'ai' });
    expect((await tool(s.accessToken, 'update_exercise', { id: 'USER_TOWELROW01', notes: 'Door anchor' })).error).toBeUndefined();

    expect((await tool(s.accessToken, 'update_exercise', { id: 'PUSH_UP', name: 'x' })).error).toContain('built-in');
    expect((await tool(s.accessToken, 'update_exercise', { id: 'USER_TOWELROW01', name: 'Push-up' })).error).toContain('exists already');
    expect((await tool(s.accessToken, 'update_exercise', { id: ex.id, primary: ['QUADS'] })).error).toContain('Nothing to change');
    expect((await tool(s.accessToken, 'update_exercise', { id: 'USER_TOWELROW01', timed: true })).error).toBeUndefined();
    await tool(s.accessToken, 'create_workout', { name: 'Stretch', rows: [{ exerciseId: ex.id, sets: 2, seconds: 45 }] });
    expect((await tool(s.accessToken, 'update_exercise', { id: ex.id, timed: false })).error).toContain('timed can only change');

    const ops = await pull(s.spaceToken);
    expect(ops.map((o) => o.op.type)).toEqual(['create_exercise', 'update_exercise', 'update_exercise', 'update_exercise', 'create_workout']);
    expect(ops[2].op).toMatchObject({ ref: 'USER_TOWELROW01', notes: 'Door anchor' });
  });

  it('refuses changes outside the AI\'s scope with a reason', async () => {
    const s = await connected();
    expect((await tool(s.accessToken, 'update_session', { id: 's11', durationMin: 60 })).error).toContain('locked');
    expect((await tool(s.accessToken, 'delete_session', { id: 's9' })).error).toContain('done');
    expect((await tool(s.accessToken, 'delete_session', { id: 's404' })).error).toContain('No planned session');
    expect((await tool(s.accessToken, 'update_workout', { id: 'w1', name: 'Mine now' })).error).toContain('not a workout the AI created');
    expect((await tool(s.accessToken, 'create_exercise', { name: 'push-up', primary: ['CHEST'] })).error).toContain('PUSH_UP');
    expect((await tool(s.accessToken, 'schedule_sessions', { sessions: [{ day: '2026-11-20', sport: 'WALK' }] })).error).toContain('past');
    expect((await tool(s.accessToken, 'schedule_sessions', { sessions: [{ day: '2026-12-03', sport: 'CYCLING', sessionType: 'LONG_RUN' }] })).error).toContain('does not fit');
    const batch = await tool(s.accessToken, 'schedule_sessions', { sessions: [{ day: '2026-12-03', sport: 'WALK' }, { day: 'soon', sport: 'WALK' }] });
    expect(batch.error).toContain('Item 2');
    expect(await pull(s.spaceToken)).toEqual([]);
  });

  it('may replace the owner\'s unlocked sessions', async () => {
    const s = await connected();
    expect((await tool(s.accessToken, 'update_session', { id: 's10', durationMin: 30, rationale: 'Lighter' })).status).toBe('pending');
    expect((await tool(s.accessToken, 'delete_session', { id: 's12' })).status).toBe('pending');
    const cal = await tool(s.accessToken, 'get_calendar', {});
    expect(cal.sessions.map((x: any) => x.id)).toEqual(['s10', 's11']);
    expect(cal.sessions[0].durationMin).toBe(30);
  });
});

describe('the phone applies and reports back', () => {
  it('applied ops stay in the view until a newer snapshot, rejected ones show their reason', async () => {
    const s = await connected();
    const a = await tool(s.accessToken, 'schedule_sessions', { sessions: [{ day: '2026-12-03', sport: 'MOBILITY', durationMin: 20 }] });
    await tool(s.accessToken, 'update_session', { id: 's10', durationMin: 50 });
    const ops = await pull(s.spaceToken);
    expect((await ack(s.spaceToken, [
      { opId: ops[0].opId, status: 'applied' },
      { opId: ops[1].opId, status: 'rejected', reason: 'Session s10 is locked by the owner and stays as it is.' },
    ])).status).toBe(200);
    expect(await pull(s.spaceToken)).toEqual([]);

    let cal = await tool(s.accessToken, 'get_calendar', {});
    expect(cal.sessions.some((x: any) => x.id === a.ids[0])).toBe(true);
    expect(cal.sessions.find((x: any) => x.id === 's10').durationMin).toBe(45);

    const sync = await tool(s.accessToken, 'get_sync_status');
    expect(sync.recent.map((r: any) => r.status)).toEqual(['rejected', 'applied']);
    expect(sync.recent[0].reason).toContain('locked');

    // The phone's next snapshot carries the session as s77 with the link.
    const next = structuredClone(snapshot);
    next.sessions.push({ ...next.sessions[0], id: 's77', day: '2026-12-03', sport: 'MOBILITY', sessionType: 'MOBILITY', status: 'PLANNED', changeable: true, byAi: true });
    next.links = { [a.ids[0]]: 's77' };
    await putSnapshot(s.spaceToken, next);
    cal = await tool(s.accessToken, 'get_calendar', {});
    expect(cal.sessions.filter((x: any) => x.day === '2026-12-03').map((x: any) => x.id)).toEqual(['s77']);
    // The ai id keeps working after the phone applied it.
    expect((await tool(s.accessToken, 'update_session', { id: a.ids[0], durationMin: 25 })).status).toBe('pending');
    expect((await pull(s.spaceToken))[0].op.ref).toBe(a.ids[0]);
  });

  it('a space cannot see another space\'s data', async () => {
    const a = await connected();
    const b = await connected();
    await tool(a.accessToken, 'schedule_sessions', { sessions: [{ day: '2026-12-03', sport: 'WALK' }] });
    expect(await pull(b.spaceToken)).toEqual([]);
    expect((await tool(b.accessToken, 'get_sync_status')).pending).toBe(0);
  });

  it('disconnect wipes the space and revokes the connector', async () => {
    const s = await connected();
    expect((await call('/v1/space', { method: 'DELETE', headers: auth(s.spaceToken) })).status).toBe(200);
    expect((await call('/v1/status', { headers: auth(s.spaceToken) })).status).toBe(401);
    const r = await mcp(s.accessToken, 'tools/list');
    expect(r.httpStatus).toBe(401);
  });

  it('enforces the daily change limit', async () => {
    const s = await connected();
    const sessions = Array.from({ length: 60 }, (_, i) => ({ day: '2026-12-10', sport: 'WALK', description: `walk ${i}` }));
    for (let i = 0; i < 3; i++) expect((await tool(s.accessToken, 'schedule_sessions', { sessions })).status).toBe('pending');
    expect((await tool(s.accessToken, 'schedule_sessions', { sessions })).error).toContain('Daily limit');
  });
});
