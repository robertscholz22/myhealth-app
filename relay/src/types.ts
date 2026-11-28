// The phone's snapshot (Kotlin `AiSnapshot`) and the ops the relay queues (Kotlin `AiOp`).
// Field names match the Kotlin @Serializable classes exactly: they are the wire format.

export interface Vocab {
  muscles: string[];
  equipment: string[];
  patterns: string[];
  workoutKinds: string[];
  intensities: string[];
  sports: Record<string, string[]>;
  workoutSessionTypes: string[];
  typeIntensity: Record<string, string>;
  limits: Record<string, number>;
}

export interface ExerciseDto {
  id: string;
  name: string;
  primary: string[];
  secondary: string[];
  equipment: string;
  pattern: string;
  timed: boolean;
  oneSided: boolean;
  /** catalog = built into the app; mine = made by the owner; ai = made by you (the AI); family = shared by a family member. */
  source: 'catalog' | 'mine' | 'ai' | 'family';
  /** You created it. */
  byAi?: boolean;
  /** update_exercise may change it. */
  editable?: boolean;
  notes?: string;
  pending?: boolean;
}

export interface WorkoutRow {
  exerciseId: string;
  sets: number;
  reps?: number;
  seconds?: number;
  loadKg?: number;
  restSec?: number;
  note?: string;
  sourceLine?: string;
}

export interface WorkoutDto {
  id: string;
  name: string;
  kind: string;
  minutes: number;
  rows: WorkoutRow[];
  video: boolean;
  byAi: boolean;
  notes?: string;
  videoUrl?: string;
  videoTitle?: string;
  videoMinutes?: number;
  pending?: boolean;
}

export interface SessionFields {
  day?: string;
  start?: string;
  sport?: string;
  sessionType?: string;
  intensity?: string;
  durationMin?: number;
  distanceKm?: number;
  pace?: string;
  workout?: string;
  description?: string;
  rationale?: string;
}

export interface SessionDto {
  id: string;
  day: string;
  start?: string;
  sport: string;
  sessionType: string;
  intensity: string;
  durationMin?: number;
  distanceKm?: number;
  pace?: string;
  loadAu?: number;
  workout?: string;
  description?: string;
  rationale?: string;
  status: string;
  locked: boolean;
  changeable: boolean;
  byAi: boolean;
  pending?: boolean;
}

export interface Snapshot {
  version: number;
  generatedAtMillis: number;
  today: string;
  groups: string[];
  window: { from: string; to: string };
  sports: string[];
  equipment: string[];
  exercises: ExerciseDto[];
  workouts: WorkoutDto[];
  goals: unknown[];
  sessions: SessionDto[];
  events: { day: string; start?: string; title: string; type: string; sport?: string; intensity?: string; key: boolean; durationMin?: number }[];
  activities: { day: string; sport: string; durationMin: number; distanceKm?: number; loadAu?: number; avgHr?: number }[];
  links: Record<string, string>;
  load?: unknown;
  body?: unknown;
  cycle?: unknown;
  vocab: Vocab;
}

export type Op =
  | { type: 'create_exercise'; opId: string; aiId: string; name: string; primary: string[]; secondary?: string[]; equipment?: string; pattern?: string; timed?: boolean; oneSided?: boolean; notes?: string }
  | { type: 'set_equipment'; opId: string; equipment: string[] }
  | { type: 'update_exercise'; opId: string; ref: string; name?: string; primary?: string[]; secondary?: string[]; equipment?: string; pattern?: string; timed?: boolean; oneSided?: boolean; notes?: string }
  | { type: 'create_workout'; opId: string; aiId: string; name: string; kind?: string; rows: WorkoutRow[]; notes?: string; videoUrl?: string; videoTitle?: string; videoMinutes?: number; sourceText?: string }
  | { type: 'update_workout'; opId: string; ref: string; name?: string; kind?: string; rows?: WorkoutRow[]; notes?: string; videoUrl?: string; videoTitle?: string; videoMinutes?: number; sourceText?: string }
  | { type: 'delete_workout'; opId: string; ref: string }
  | { type: 'schedule_session'; opId: string; aiId: string; session: SessionFields }
  | { type: 'update_session'; opId: string; ref: string; session: SessionFields }
  | { type: 'delete_session'; opId: string; ref: string };

export type OpStatus = 'pending' | 'applied' | 'rejected';

export interface Env {
  SPACE: DurableObjectNamespace<import('./space').Space>;
  OAUTH_KV: KVNamespace;
  /** Worker secret: required to register a phone's space. */
  REGISTRATION_KEY: string;
  OAUTH_PROVIDER: import('@cloudflare/workers-oauth-provider').OAuthHelpers;
}
