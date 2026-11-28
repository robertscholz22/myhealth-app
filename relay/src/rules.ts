// The relay's copy of the phone's rules (Kotlin `AiOpPlanner`), for an immediate answer to the AI.
// The phone checks every op again and decides; this only keeps obviously wrong ops out of the queue
// and lets the conversation see its own changes before the phone has synced.
import type { ExerciseDto, Op, SessionDto, SessionFields, Snapshot, Vocab, WorkoutDto, WorkoutRow } from './types';

export class OpError extends Error {}

const fail = (message: string): never => {
  throw new OpError(message);
};

/** The snapshot plus every op the phone has not reported back in a newer snapshot. */
export interface View {
  snapshot: Snapshot;
  exercises: ExerciseDto[];
  workouts: WorkoutDto[];
  sessions: SessionDto[];
  equipment: string[];
  /** ai id → current id in the view (the snapshot's local id once applied, else the ai id). */
  ids: Record<string, string>;
}

export function buildView(snapshot: Snapshot, ops: Op[]): View {
  const view: View = {
    snapshot,
    exercises: snapshot.exercises.map((e) => ({ ...e })),
    workouts: snapshot.workouts.map((w) => ({ ...w, rows: w.rows.map((r) => ({ ...r })) })),
    sessions: snapshot.sessions.map((s) => ({ ...s })),
    equipment: [...snapshot.equipment],
    ids: { ...snapshot.links },
  };
  for (const op of ops) {
    try {
      applyOp(view, op);
    } catch {
      // An op the phone has since made impossible (the session got locked): the phone rejects it too.
    }
  }
  return view;
}

/** Checks [op] against the view and applies it; throws OpError with a reason the AI can act on. */
export function applyOp(view: View, op: Op): void {
  const v = view.snapshot.vocab;
  switch (op.type) {
    case 'create_exercise': {
      newAiId(view, op.aiId);
      const name = text(op.name, v.limits.maxName, 'The exercise needs a name of up to %d characters.');
      if (!op.primary?.length) fail('Give at least one primary muscle.');
      const primary = op.primary.map((m) => oneOf(m, v.muscles, 'muscle'));
      const secondary = (op.secondary ?? []).map((m) => oneOf(m, v.muscles, 'muscle')).filter((m) => !primary.includes(m));
      const equipment = oneOf(op.equipment ?? 'BODYWEIGHT', v.equipment, 'equipment');
      const pattern = oneOf(op.pattern ?? 'OTHER', v.patterns, 'movement pattern');
      const same = view.exercises.find((e) => e.name.toLowerCase() === name.toLowerCase());
      if (same) fail(`An exercise named "${same.name}" exists already (id ${same.id}); use it instead.`);
      view.exercises.push({
        id: op.aiId, name, primary, secondary, equipment, pattern, timed: !!op.timed, oneSided: !!op.oneSided,
        source: 'ai', byAi: true, editable: true, notes: op.notes, pending: true,
      });
      view.ids[op.aiId] = op.aiId;
      return;
    }
    case 'update_exercise': {
      const e = exercise(view, op.ref) ?? fail(`No exercise ${op.ref}; list_exercises shows the ids.`);
      const editable = e.editable ?? (e.source === 'ai' || e.source === 'mine');
      if (e.source === 'catalog') fail(`${op.ref} is a built-in exercise and stays as it is; create a new one instead.`);
      if (e.source === 'family' || !editable) fail(`${e.name} is shared by a family member and read-only; create a new one instead.`);
      const next = { ...e };
      if (op.name !== undefined) {
        next.name = text(op.name, v.limits.maxName, 'The exercise needs a name of up to %d characters.');
        const same = view.exercises.find((x) => x.id !== e.id && x.name.toLowerCase() === next.name.toLowerCase());
        if (same) fail(`An exercise named "${same.name}" exists already (id ${same.id}).`);
      }
      if (op.primary !== undefined) {
        if (!op.primary.length) fail('Give at least one primary muscle.');
        next.primary = op.primary.map((m) => oneOf(m, v.muscles, 'muscle'));
      }
      if (op.secondary !== undefined) next.secondary = op.secondary.map((m) => oneOf(m, v.muscles, 'muscle'));
      next.secondary = next.secondary.filter((m) => !next.primary.includes(m));
      if (op.equipment !== undefined) next.equipment = oneOf(op.equipment, v.equipment, 'equipment');
      if (op.pattern !== undefined) next.pattern = oneOf(op.pattern, v.patterns, 'movement pattern');
      if (op.timed !== undefined && op.timed !== e.timed) {
        const users = view.workouts.filter((w) => w.rows.some((r) => resolve(view, r.exerciseId) === e.id));
        if (users.length) fail(`${e.name} is used by ${users.map((w) => `"${w.name}"`).join(', ')} with ${e.timed ? 'seconds' : 'reps'}; timed can only change while no workout uses it.`);
        next.timed = op.timed;
      }
      if (op.oneSided !== undefined) next.oneSided = op.oneSided;
      if (op.notes !== undefined) next.notes = op.notes.trim() || undefined;
      const same = (a: unknown, b: unknown) => JSON.stringify(a) === JSON.stringify(b);
      if (same({ ...next, pending: undefined }, { ...e, pending: undefined })) fail(`Nothing to change for ${e.name}.`);
      Object.assign(e, next, { pending: true });
      return;
    }
    case 'set_equipment': {
      const set = new Set(op.equipment.map((e) => oneOf(e, v.equipment, 'equipment')));
      set.add('BODYWEIGHT');
      view.equipment = v.equipment.filter((e) => set.has(e));
      return;
    }
    case 'create_workout': {
      newAiId(view, op.aiId);
      const name = text(op.name, v.limits.maxName, 'The workout needs a name of up to %d characters.');
      const kind = oneOf(op.kind ?? 'CUSTOM', v.workoutKinds, 'workout kind');
      const rows = checkRows(view, op.rows);
      const w: WorkoutDto = { id: op.aiId, name, kind, minutes: minutes(rows), rows, video: false, byAi: true, notes: op.notes, pending: true };
      applyVideo(view, w, op);
      view.workouts.push(w);
      view.ids[op.aiId] = op.aiId;
      return;
    }
    case 'update_workout': {
      const w = aiWorkout(view, op.ref);
      if (op.name !== undefined) w.name = text(op.name, v.limits.maxName, 'The workout needs a name of up to %d characters.');
      if (op.kind !== undefined) w.kind = oneOf(op.kind, v.workoutKinds, 'workout kind');
      if (op.rows !== undefined) {
        w.rows = checkRows(view, op.rows);
        w.minutes = w.videoMinutes ?? minutes(w.rows);
      }
      if (op.notes !== undefined) w.notes = op.notes;
      applyVideo(view, w, op);
      w.pending = true;
      return;
    }
    case 'delete_workout': {
      const w = aiWorkout(view, op.ref);
      const users = view.sessions.filter((s) => s.workout === w.id);
      if (users.length) fail(`Sessions use this workout (${users.map((s) => s.id).join(', ')}); delete or change them first.`);
      view.workouts = view.workouts.filter((x) => x !== w);
      return;
    }
    case 'schedule_session': {
      newAiId(view, op.aiId);
      const f = op.session;
      if (!f.day) fail('A new session needs a day (YYYY-MM-DD).');
      if (!f.sport && !f.workout) fail('A new session needs a sport.');
      const base: SessionDto = {
        id: op.aiId, day: view.snapshot.today, sport: 'STRENGTH', sessionType: 'STRENGTH_FULL', intensity: 'MODERATE',
        status: 'PLANNED', locked: false, changeable: true, byAi: true, pending: true,
      };
      view.sessions.push(merge(view, base, f, true));
      view.ids[op.aiId] = op.aiId;
      return;
    }
    case 'update_session': {
      const s = changeableSession(view, op.ref);
      Object.assign(s, merge(view, s, op.session, false), { pending: true });
      return;
    }
    case 'delete_session': {
      const s = changeableSession(view, op.ref);
      view.sessions = view.sessions.filter((x) => x !== s);
      return;
    }
    default:
      fail('Unknown change.');
  }
}

function newAiId(view: View, aiId: string) {
  if (!aiId.startsWith('ai-')) fail('New rows need an id starting with "ai-".');
  if (view.ids[aiId]) fail(`${aiId} exists already.`);
}

/** A snapshot id, or the id an `ai-…` ref has in the view. */
export function resolve(view: View, ref: string): string {
  return ref.startsWith('ai-') ? (view.ids[ref] ?? ref) : ref;
}

function aiWorkout(view: View, ref: string): WorkoutDto {
  const id = resolve(view, ref);
  const w = view.workouts.find((x) => x.id === id) ?? fail(`No workout ${ref}.`);
  if (!w.byAi) fail(`"${w.name}" is not a workout the AI created; create a new one instead.`);
  return w;
}

function changeableSession(view: View, ref: string): SessionDto {
  const id = resolve(view, ref);
  const s = view.sessions.find((x) => x.id === id) ?? fail(`No planned session ${ref}.`);
  if (s.locked) fail(`Session ${ref} is locked by the owner and stays as it is.`);
  if (s.status !== 'PLANNED') fail(`Session ${ref} is done or skipped and stays as it is.`);
  if (s.day < view.snapshot.today) fail(`Session ${ref} is in the past and stays as it is.`);
  if (!s.changeable) fail(`Session ${ref} stays as it is.`);
  return s;
}

function exercise(view: View, ref: string): ExerciseDto | undefined {
  return view.exercises.find((e) => e.id === resolve(view, ref));
}

function checkRows(view: View, rows: WorkoutRow[]): WorkoutRow[] {
  const l = view.snapshot.vocab.limits;
  if (!rows?.length || rows.length > l.maxRows) fail(`A workout needs 1 to ${l.maxRows} exercises.`);
  return rows.map((r) => {
    const e = exercise(view, r.exerciseId) ?? fail(`Unknown exercise ${r.exerciseId}; list_exercises shows the ids.`);
    if (!int(r.sets, 1, l.maxSets)) fail(`${e.name}: sets must be 1–${l.maxSets}.`);
    if (r.reps != null && r.seconds != null) fail(`${e.name}: give reps or seconds, not both.`);
    if (r.reps == null && r.seconds == null) fail(`${e.name}: give ${e.timed ? 'seconds' : 'reps'}.`);
    if (r.reps != null && e.timed) fail(`${e.name} is timed: give seconds.`);
    if (r.reps != null && !int(r.reps, 1, l.maxReps)) fail(`${e.name}: reps must be 1–${l.maxReps}.`);
    if (r.seconds != null && !int(r.seconds, l.minSeconds, l.maxSeconds)) fail(`${e.name}: seconds must be ${l.minSeconds}–${l.maxSeconds}.`);
    if (r.loadKg != null && !(r.loadKg >= 0 && r.loadKg <= l.maxLoadKg)) fail(`${e.name}: load must be 0–${l.maxLoadKg} kg.`);
    if (r.restSec != null && !int(r.restSec, 0, l.maxRestSec)) fail(`${e.name}: rest must be 0–${l.maxRestSec} s.`);
    // Rows keep the id the AI used; the phone resolves `ai-…` ids when it applies the op.
    return { ...r };
  });
}

interface VideoFields { videoUrl?: string; videoTitle?: string; videoMinutes?: number; sourceText?: string }

/** Mirror of the Kotlin planner's video rules; the phone normalises YouTube links. */
function applyVideo(view: View, w: WorkoutDto, f: VideoFields): void {
  const l = view.snapshot.vocab.limits;
  const removing = f.videoUrl !== undefined && f.videoUrl.trim() === '';
  if (removing) {
    w.video = false; w.videoUrl = undefined; w.videoTitle = undefined; w.videoMinutes = undefined;
    w.minutes = minutes(w.rows);
    return;
  }
  if (f.videoUrl !== undefined) {
    const u = f.videoUrl.trim();
    if (u.length > (l.maxUrl ?? 500) || !/^https:\/\/[^\s]+$/i.test(u)) fail('videoUrl must be an https link to the video, e.g. https://www.youtube.com/watch?v=….');
    w.videoUrl = u; w.video = true;
  }
  const any = f.videoTitle !== undefined || f.videoMinutes !== undefined || f.sourceText !== undefined;
  if (!w.video) {
    if (any) fail('videoTitle, videoMinutes and sourceText need a videoUrl.');
    return;
  }
  if (f.videoTitle !== undefined && f.videoTitle.trim().length > (l.maxVideoTitle ?? 200)) fail(`videoTitle: up to ${l.maxVideoTitle ?? 200} characters.`);
  if (f.videoMinutes !== undefined && !int(f.videoMinutes, 1, l.maxDurationMin)) fail(`videoMinutes must be 1–${l.maxDurationMin}.`);
  if (f.sourceText !== undefined && f.sourceText.length > (l.maxSourceText ?? 5000)) fail(`sourceText: up to ${l.maxSourceText ?? 5000} characters; keep the exercise part.`);
  if (f.videoTitle?.trim()) w.videoTitle = f.videoTitle.trim();
  if (f.videoMinutes !== undefined) w.videoMinutes = f.videoMinutes;
  w.minutes = w.videoMinutes ?? minutes(w.rows);
}

/** The phone's estimate (Kotlin `StrengthWorkout.estimatedMinutes`): 3 s per rep, 90 s rest, 8 min overhead. */
function minutes(rows: WorkoutRow[]): number {
  const sec = rows.reduce((t, r) => t + r.sets * ((r.seconds ?? (r.reps ?? 0) * 3) + (r.restSec ?? 90)), 0);
  return Math.ceil((sec + 480) / 60);
}

function merge(view: View, base: SessionDto, f: SessionFields, isNew: boolean): SessionDto {
  const v: Vocab = view.snapshot.vocab;
  const l = v.limits;
  const today = view.snapshot.today;
  const day = f.day !== undefined ? (isoDay(f.day) ?? fail(`Day ${f.day} is not a date (YYYY-MM-DD).`)) : base.day;
  if (day < today) fail(`Day ${f.day} is in the past.`);
  if (daysBetween(today, day) > l.maxDaysAhead) fail('Plan at most a year ahead.');
  let start = base.start;
  if (f.start !== undefined) start = f.start === '' ? undefined : (hhmm(f.start) ?? fail(`Start ${f.start} is not a time (HH:MM).`));
  let workout = base.workout;
  if (f.workout !== undefined) {
    if (f.workout === '' || f.workout === 'none') workout = undefined;
    else {
      const id = resolve(view, f.workout);
      workout = (view.workouts.find((w) => w.id === id) ?? fail(`No workout ${f.workout}.`)).id;
    }
  }
  const w = workout ? view.workouts.find((x) => x.id === workout) : undefined;
  let sport: string;
  if (f.sport !== undefined) {
    sport = f.sport.trim().toUpperCase();
    if (!v.sports[sport]) fail(`Unknown sport ${f.sport}. Use: ${Object.keys(v.sports).join(', ')}.`);
  } else sport = isNew && w ? (w.kind.startsWith('MOBILITY') ? 'MOBILITY' : 'STRENGTH') : base.sport;
  const types = v.sports[sport] ?? ['CROSS_TRAINING'];
  let type: string;
  if (f.sessionType !== undefined) type = f.sessionType.trim().toUpperCase();
  else if (w && f.workout !== undefined) type = workoutType(w.kind);
  else if (!isNew && f.sport === undefined) type = base.sessionType;
  else type = types[0];
  if (w && (!v.workoutSessionTypes.includes(type) || !types.some((t) => v.workoutSessionTypes.includes(t)))) {
    fail('A workout can only be planned as a strength or mobility session.');
  }
  if (!types.includes(type)) fail(`Session type ${type} does not fit ${sport}; use one of ${types.join(', ')}.`);
  let intensity = base.intensity;
  if (f.intensity !== undefined) intensity = oneOf(f.intensity, v.intensities, 'intensity');
  else if (isNew || f.sport !== undefined || f.sessionType !== undefined) intensity = v.typeIntensity[type] ?? base.intensity;
  const duration = f.durationMin ?? base.durationMin ?? w?.minutes;
  if (duration != null && !int(duration, 1, l.maxDurationMin)) fail(`Duration must be 1–${l.maxDurationMin} minutes.`);
  const distance = f.distanceKm ?? base.distanceKm;
  if (f.distanceKm != null && !(f.distanceKm > 0 && f.distanceKm <= l.maxDistanceKm)) fail(`Distance must be more than 0 and at most ${l.maxDistanceKm} km.`);
  let pace = base.pace;
  if (f.pace !== undefined) {
    if (f.pace === '') pace = undefined;
    else {
      const sec = paceSec(f.pace) ?? fail(`Pace ${f.pace} is not m:ss per km.`);
      if (sec < l.minPaceSec || sec > l.maxPaceSec) fail('Pace must be between 2:00 and 15:00 per km.');
      pace = f.pace.trim();
    }
  }
  return {
    ...base, day, start, sport, sessionType: type, intensity, durationMin: duration, distanceKm: distance, pace, workout,
    description: f.description ?? base.description, rationale: f.rationale ?? base.rationale,
    // The phone recomputes the load estimate from its catalog.
    loadAu: f.durationMin !== undefined || f.sessionType !== undefined || f.intensity !== undefined ? undefined : base.loadAu,
  };
}

function workoutType(kind: string): string {
  if (kind.startsWith('MOBILITY')) return 'MOBILITY';
  if (kind === 'UPPER') return 'STRENGTH_UPPER';
  if (kind === 'LOWER') return 'STRENGTH_LOWER';
  return 'STRENGTH_FULL';
}

function oneOf(value: string, allowed: string[], what: string): string {
  const key = String(value).trim().toUpperCase().replace(/[ -]/g, '_');
  if (!allowed.includes(key)) fail(`Unknown ${what} ${value}. Use: ${allowed.join(', ')}.`);
  return key;
}

function text(value: string, max: number, message: string): string {
  const t = (value ?? '').trim();
  if (!t || t.length > max) fail(message.replace('%d', String(max)));
  return t;
}

const int = (n: unknown, min: number, max: number) => Number.isInteger(n) && (n as number) >= min && (n as number) <= max;

export function isoDay(s: string): string | undefined {
  const m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(s.trim());
  if (!m) return undefined;
  const d = new Date(Date.UTC(+m[1], +m[2] - 1, +m[3]));
  return d.getUTCMonth() === +m[2] - 1 && d.getUTCDate() === +m[3] ? s.trim() : undefined;
}

export function daysBetween(a: string, b: string): number {
  return Math.round((Date.parse(b + 'T00:00:00Z') - Date.parse(a + 'T00:00:00Z')) / 86_400_000);
}

function hhmm(s: string): string | undefined {
  const m = /^([01]?\d|2[0-3]):([0-5]\d)$/.exec(s.trim());
  return m ? `${m[1].padStart(2, '0')}:${m[2]}` : undefined;
}

function paceSec(s: string): number | undefined {
  const m = /^(\d{1,2}):([0-5]\d)$/.exec(s.trim());
  return m ? +m[1] * 60 + +m[2] : undefined;
}
