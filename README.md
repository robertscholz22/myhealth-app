# MyHealth

Native Android app (Kotlin, Jetpack Compose, Room) for a Google Pixel 7A: a personal hub for sport performance, nutrition and health. Everything runs on the phone; there is no server.

## What it does

- **Garmin data via Health Connect** — Garmin Connect writes workouts, heart rate, steps, calories, sleep, weight and more into Health Connect; the app syncs incrementally (changes tokens), backfills history, and deduplicates against FIT/CSV imports.
- **Activities** — list and detail with HR zones, laps, TRIMP; **training load** (ATL/CTL/ACWR, monotony, strain), **recovery score** (sleep, resting HR, load, HRV), **running PRs** with best-split detection, Riegel predictions and VDOT.
- **Calendar** — soccer matches, trainings, races and other events (with weekly recurrence), linked to the matching Garmin activity automatically or by hand.
- **Nutrition** — ingredients entered by hand, scanned from a nutrition label (camera OCR, German + English), from a photo, or looked up by barcode (Open Food Facts); meal templates; a diary with per-slot logging and water; **adaptive daily targets** (calories and protein/carb/fat split) from weight, goal weight, activity level, measured expenditure and the day's context (rest, training, pre-match, match day).
- **Training plans** — goals (race time, weight, consistency, bike FTP / volume / event), a week board, and rule-based **session suggestions** with rationale, driven by goals, load, recovery and the calendar (no hard sessions before a match, taper before races, rest days, sport caps).
- **Import / backup** — Garmin `.fit`, activity `.csv` and full export `.zip`; JSON backup export/import.
- **Heart-rate zones and paces** — five zones from your heart-rate reserve (or lactate threshold / manual bounds), a target zone and target pace per session type, pace bands per zone measured from your runs and blended with Daniels paces from your VDOT, a Zones & paces screen with a 28-day easy/hard split.
- **Structured intervals** — interval, tempo, bike-interval and trainer sessions come with warm-up, reps, recovery and cool-down, with pace, zone or power targets.
- **Strength** — a 54-exercise catalog with a front/back body figure showing the muscles used, workouts composed of exercises (six built-in templates, your own editor, set logging), per-muscle-group load with a heat map, and strength suggestions that avoid a leg day right after a hard run or before a match.
- **My equipment and progression** — tell the app what equipment you own and exercises, pickers and workouts stick to it; every exercise gets a suggested load and rep count that progresses from your feedback after each session (too easy / easy / hard / too hard).
- **Exercise animations** — every built-in exercise animates on the body figure (front, back or profile view), on exercise detail and while you log a set.
- **Mobility** — 33 mobility exercises with the muscles they target, three routines (lower, upper, full body, about 20 minutes), and rest-day mobility sessions that pick the routine from your muscle load.
- **Active recovery** — rest days (all but one true rest day per week) get an easy recovery run or spin on top of mobility, alternating sports, cycle-aware, never before a match or on a strained day.
- **Cycling** — rides from Health Connect, FIT or CSV carry average/normalized/max power and cadence; FTP is estimated (best 20-minute power × 0.95 over 90 days, or session normalized power, with a manual override in Settings); ride bests (5/20/60-minute power, fastest 10/20/40/100 km) on the Bike & power screen; training load from power (TSS) when a ride has no heart rate; bike goals (FTP target, weekly ride hours, event with distance/time/date) and ride sessions in the suggested week (endurance ride, bike intervals, trainer session, recovery spin — indoor variants in November–March when an indoor trainer is available).
- **Cycle tracker** (female users, or anyone who enables it) — log period starts and ends, get averages-based forecasts of the next periods, ovulation and fertile windows on the calendar and Today, and cycle-aware training suggestions (moderate intensity on the first period days, strength/intervals favoured in the follicular phase, warm-up note around ovulation, recovery focus in the late luteal phase).
- Green Material 3 theme (wallpaper colours optional).

## Build and install

Requirements: JDK 21, Android SDK with platform 36 (see `docs/TOOLCHAIN.md` for the exact, verified toolchain).

```bash
export JAVA_HOME=/path/to/jdk21 ANDROID_HOME=/path/to/android-sdk
./gradlew :app:assembleDebug            # debug APK
./gradlew :app:assembleRelease          # minified release APK (debug-signed, for sideloading)
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

On first launch complete onboarding, then More → Integrations → Grant permissions (Health Connect) → Sync now. Make sure Garmin Connect is allowed to write to Health Connect on the phone.

## Releases

Stable releases are annotated git tags `vX.Y.Z` on `main` (the phone is only ever installed from a tag, never from main):

```bash
git checkout v0.2.1 && bash tools/verify.sh
adb install -r app/build/outputs/apk/release/app-release.apk
git checkout main
```

Each release is also published on GitHub with the APK attached. This repository is public since 0.9.3 and starts with that release; the earlier history and releases are kept in a private archive repository because they contain the owner's personal data. **Never commit personal data here**: no backups, health or Garmin exports, and no screenshots taken on a personal phone or with personal data restored. Numbering restarted at 0.1.0 on 2026-09-13 (the first six releases were renumbered from 1.0.0…1.1.1 to 0.1.0…0.2.1); the Android `versionCode` keeps increasing regardless. A `release/X.Y.x` branch is only created from a tag when a hotfix is needed while main has moved on.

## Verify

```bash
bash tools/verify.sh                     # assembleDebug + unit tests + lint (+ release)
bash tools/connected.sh emulator-5554   # instrumented tests — ONLY on a named emulator (the raw Gradle task installs/uninstalls on every attached device, phone included)
```

Emulator harness for runtime testing: `tools/emu.sh`, `tools/ui.sh`, and the Health Connect seeder app in `tools/hc-seeder/` (`seed.sh up && seed.sh seed 45`). Results are logged in `docs/VERIFICATION.md` with screenshots in `docs/screenshots/`.

## Layout

```
app/src/main/java/com/myhealth/
  domain/   pure Kotlin: models, engines (nutrition targets, TRIMP/ACWR, recovery, PRs, suggestions, label parser)
  data/     Room DB, repositories, Health Connect, FIT/CSV, OCR, Open Food Facts, backup
  sync/     WorkManager workers (HC sync, target/load recompute, import)
  ui/       Compose screens + ViewModels (no imports from data/)
  di/       AppGraph (manual DI)
docs/       BRIEF.md (requirements), PLAN.md (architecture + task plan), TOOLCHAIN.md, STATUS.md, VERIFICATION.md
```
