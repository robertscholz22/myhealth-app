# Runtime verification log (emulator myhealth_api35, Android 15)

Build under test is recorded per session. Findings are triaged as BUG (must fix), POLISH (nice to fix), NOTE (informational).

## Session 1 — 2026-09-12 (build after P6.1–P6.4, tests 554)

| Step | Result | Evidence |
|---|---|---|
| Install + first launch → Onboarding step 1 | PASS | screenshots/01_first_launch.png |
| Onboarding step 1 validation blocks Next without birth date | PASS | 02_onboarding_step2.png (stayed on step 1) |
| Date picker text-input mode, birth date accepted | PASS | 02_datepicker_input.png |
| Onboarding steps 2–3, Finish → Today | PASS | 03_step2_filled.png, 04_step3_filled.png, 05_today_first.png |
| Today: nutrition target computed (2320 kcal / 120 P / 265 C / 85 F for 76 kg, 180 cm, light active, maintain) | PASS (matches Mifflin: BMR 1720 × 1.35) | 05_today_first.png |
| Bottom tabs Calendar / Nutrition / Training / More | PASS (Training = placeholder until P6.6) | 06_tab_*.png |
| Nutrition diary: target header, water target 2.7 l, empty state | PASS | 06_tab_Nutrition.png |
| More → Integrations / Settings / Body / Goals / Ingredients / Meal Templates / Activities / Load & Recovery / Running PRs | PASS, no crashes | 07_more_*.png |
| Integrations: Health Connect "Available", all permissions granted | PASS (granted by `install -g`; real grant sheet retested later) | 07_more_Integrations.png |
| Green theme applied (light) | PASS | all screenshots |

Findings:
- POLISH-1: Ingredient editor shows validation warnings ("Fewer than 3 nutrient fields…", "No energy value…") on a completely empty new form. Show warnings only once any nutrient field has been touched.
- NOTE-1: uiautomator does not expose Compose text-field placeholders (e.g. "Birth date"); automation taps by coordinates there. Not a user-facing issue.
- BUG-1 (backend, Health Connect): with zero records in Health Connect, `aggregateGroupByPeriod(TotalCaloriesBurnedRecord.ENERGY_TOTAL)` still returns ~1564.5 kcal per day (the platform synthesises a basal-metabolic baseline). `HcAggregates`/`HcSyncService` stored 30 rows in `daily_health_summary` with `totalEnergyKcal = 1564.5`, source HEALTH_CONNECT, everything else null. `NutritionTargetEngine` then treated yesterday as "measured (Health Connect total)" → TDEE clamped to BMR (1720) → target 2060 with CLAMPED_TO_FLOOR instead of the 2320 estimate. On a phone this would under-feed every day Garmin did not write calories. **Fix:** in the daily aggregate reader, use `AggregationResult.dataOrigins`: when it is empty (or contains only the platform origin) treat every metric of that bucket as absent (null) and do not create a summary row for a day with no real data; keep the ≥ 0.9·BMR plausibility check in the engine as a second guard. Add a fake-reader test: bucket with a value but no data origins → no summary row. Evidence: DB dump session 1, snapshot day 20707.
- BUG-2 (UI/data, Ingredients list): a newly created ingredient does not appear in the Ingredients list (empty query) — only via search. Cause: `IngredientDao.observeRecent` filters `lastUsedAtMillis IS NOT NULL`, and `RoomIngredientRepository.search("")` delegates to it. **Fix:** empty query → all active ingredients ordered by `lastUsedAtMillis DESC NULLS LAST` (SQLite: `ORDER BY lastUsedAtMillis IS NULL, lastUsedAtMillis DESC, name ASC`), keep `observeRecent` (used-only) for the Add-food "Recents" tab. Add a fake-DAO test: never-used ingredient is listed for an empty query. Evidence: 12_after_save.png ("No ingredients yet") vs DB row id 1; 12_search_haf.png finds it.
- NOTE-2: `install -g` on the emulator pre-grants all health permissions, so the in-app "Grant permissions" button never showed; the real grant sheet is tested in a later session with a plain install.
| Ingredient editor: create by hand, decimals (13.5 / 58.7 / 0.02) persisted, edit + re-save | PASS | 09_ingredient_filled.png, DB row id 1 |
- POLISH-2 (UI, insets): `MealTemplateEditScreen` draws its top bar under the status bar (title "New template" overlaps the clock) — missing `Scaffold` content padding / `statusBarsPadding()`. The ingredient editor is correct. Audit every screen with its own top bar (templates list/edit, add-food, scan, OCR review, goal edit, planned-session edit, event edit, day detail, import, backup) and use the same Scaffold pattern as IngredientEditScreen. Evidence: 13_template_filled.png.
| Meal template: create with ingredient picker, quantity 80 g → totals 298 kcal / 11 P / 47 C / 6 F (matches MealMath), save | PASS | 13_template_filled.png, 14_templates_list.png |
- POLISH-3 (wording): the Water card's empty text "Nothing logged yet today." reads as if no meals were logged; change to "No water logged yet today."
| Template "Log now" → diary: meal under Lunch, item 80 g, totals 298/2320 kcal + macro bars updated | PASS | 16_diary_with_meal.png, 16_diary_meals.png |
| Add food: Recents tab lists used ingredient, quantity editor live preview (50 g → 187 kcal / 6.8 P), Add → Breakfast row in diary | PASS | 17_add_food.png, 17_quantity_editor.png, 18_diary_after_add.png |
| Water: +250 / +500 → 0.8 / 2.7 l (rounded from 0.75), entry list with time; header totals 485 kcal | PASS | 19_water.png |
- NOTE-3: water total is shown with one decimal (0.75 l → "0.8 l"); consider showing millilitres below 1 l or two decimals.
- BUG-4 (UI, all dropdowns): `ui/common/DropdownField.kt` puts no `Modifier.menuAnchor(...)` on the `OutlinedTextField` inside `ExposedDropdownMenuBox` and uses `DropdownMenu` instead of `ExposedDropdownMenu`, so tapping only focuses the field and the menu never opens. Affects every enum picker (onboarding sex/activity level, settings, ingredient basis, event type, meal slot, goal type, planned-session sport/type, quantity unit picker if it uses the same component). **Fix:** `modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable).fillMaxWidth()` (M3 1.4 API) and `ExposedDropdownMenu(expanded, onDismissRequest)`; then re-test one dropdown on the emulator. Evidence: 21_type_dropdown.png / 21_type_dropdown2.png (focused, no menu).
| Sync now with 45 seeded days: 38 activities (19 runs, 6 matches "Spiel"→SOCCER_MATCH, 6 trainings, 7 strength), HR streams on all, 45 sleep sessions with stages, 23 body rows, resting HR/HRV/SpO2 daily, 5k PR 20:29 + 10k PR, daily_load 29 rows, recovery 73 GOOD on Today | PASS (with BUG-5/6 below) | 24_today_after_sync.png, DB dump |
- BUG-5 (backend, load): after the initial sync only activities within the last 28 days got a TRIMP (24 of 38; `loadMethod` null on the 14 older ones) and `daily_load` starts 28 days back, so CTL warm-up ignores older history. `HealthSyncWorker`/`HcSyncService` trigger `requestLoadRecompute(today)` instead of the earliest ingested day. **Fix:** compute `minAffectedDay` from the ingested/changed records (and from backfill windows) and pass it; `LoadRecomputeService.recompute(fromDay)` must then TRIMP every activity ≥ fromDay − 28 (already does) — verify with a fake test where an activity 40 days old gets a TRIMP after sync.
- BUG-6 → NOTE-4 (resolved, emulator artifact): daily `steps/activeEnergy/floors/distance` were null after the first sync because the seeder had been granted via `adb pm grant`, which does not register it in Health Connect's data-source priority list, so aggregates excluded its data (raw reads still worked). After granting the seeder through the real Health Connect sheet and re-seeding, a second "Sync now" filled 29/30 days of steps, 28 active-kcal, 28 floors, 12 distance. On the phone Garmin Connect is granted through the sheet, so this does not occur; the raw-read fallback from fix batch 1 stays as a robustness measure.
| Clear + re-seed in Health Connect (new record ids) then Sync now: deletions + re-insertions handled, still 38 activities / 38 source records, no duplicates | PASS | DB dump |
| Activity detail (match "Spiel"): duration, calories, avg/max HR, TRIMP 226.2 (HR samples), time-in-zone table, linked-event card, title/notes edit | PASS | 27_activity_detail.png, 27_activity_detail2.png |
| Load & Recovery: ATL 65 / CTL 86 / ACWR 0.76 (Detraining) / TSB, daily TRIMP list, monotony/strain, flags with explanations, 28/90/365 d ranges | PASS | 28_load.png |
| Running PRs: 5 km 20:29, 10 km 56:30, Riegel predictions for all canonical distances, VDOT | PASS | 29_prs.png |
- POLISH-4 (running): Riegel/VDOT source effort = "largest distance ≤ 180 d" per §3.4, so a slow 10 km (56:30, VDOT 34.7) outranks a fast 5 km (20:29, VDOT ≈ 48). Prefer the effort with the highest VDOT among efforts ≥ 3 km.
| Calendar month with synced data; tap day 6 → Day detail shows the match activity (TRIMP 226) and sleep 7h14m with stages | PASS | 30_calendar_with_data.png, 31_day_detail_sep6.png |
| Body & Health: latest weight 76.0 kg (manual), last-90-days list with HC weights 77.4–78.0 kg and body-fat rows | PASS | 32_body.png |
- POLISH-5 (Body list): body-fat-only measurements render as "—, 17.4% fat"; show "Body fat 17.4 %" without the dash when weight is null.
| Calendar month: activity dots on training days, kcal bar + meal dot on today, event dot on tomorrow, today highlighted | PASS | 30_calendar_with_data.png |
| Integrations: per-channel last-sync times, "Start backfill" (365 d) completes → "Backfilled back to 2025-09-12", no errors | PASS | 33_integrations_bottom.png, 33_backfill_running.png |
- BUG-1 evidence 2: after the 365-day backfill `daily_health_summary` holds 366 rows although seeded data covers 45 days — every empty day got a row carrying only the platform's synthetic total-calorie baseline. Fix (a) in batch 1 (empty `dataOrigins` → no row) must also purge/avoid these rows; add a one-off cleanup on next sync: delete summaries whose only non-null value is `totalEnergyKcal` and whose day has no activity/steps/vitals.

## Fix batch 1 — 2026-09-12 (build after fixes, tests 579)

| Item | Status | Change | Evidence |
|---|---|---|---|
| BUG-4 (dropdowns never open) | FIXED | `ui/common/DropdownField.kt`: `Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable).fillMaxWidth()` on the `OutlinedTextField` + `ExposedDropdownMenu` instead of `DropdownMenu`. The only other `ExposedDropdownMenuBox` users were `OnboardingScreen`'s sex/NEAT pickers; both now delegate to `DropdownField`, so there is one anchor implementation left in the tree. | `screenshots/fix1_dropdown.png` (Calendar → New event → Type menu open, "Soccer match" selected → header shows it); meal-template "Default slot" menu lists all 9 slots |
| BUG-2 (new ingredient invisible) | FIXED | New `IngredientDao.observeAllActive(limit)` (`ORDER BY lastUsedAtMillis IS NULL, lastUsedAtMillis DESC, name ASC`); `RoomIngredientRepository.search("")` uses it, so the Ingredients list, the Add-food **Search** tab and the template-editor picker all list every active ingredient. `observeRecent` is untouched and still backs Add-food **Recents**. | `RoomIngredientRepositoryTest.search_with_a_blank_query_lists_a_never_used_ingredient`; `screenshots/fix1_ingredients.png` ("Haferflocken" with an empty query) |
| BUG-1 (synthetic basal baseline) | FIXED | `HcAggregates.totalsOf` returns empty totals when `AggregationResult.dataOrigins` is empty; `readDailyTotals` drops days whose five totals are all null (`HcDailyTotals.isAbsent()`), so no `daily_health_summary` row is written for them. | `HcAggregatesTest.bucket_with_a_value_but_no_data_origins_is_treated_as_absent`, `…bucket_with_a_data_origin_keeps_every_metric` |
| BUG-1 evidence 2 (366 rows after backfill) | FIXED | `HealthDao.deleteEmptySummaries()` deletes rows whose only non-null metric is `totalEnergyKcal`; exposed as `HealthRepository.deleteEmptySummaries()` and run once at the end of every `HcSyncService.syncIncremental()` (reported as `SyncSummary.emptyDaysRemoved`). | `HcSyncServiceTest.sync_deletes_daily_rows_that_only_carry_the_synthetic_energy_baseline` |
| BUG-6 → NOTE-4 (aggregates empty for an adb-granted source) | FIXED (robustness) | New `data/healthconnect/HcRawTotals.kt`: per metric, when any day's aggregate is null the raw records (`StepsRecord`, `TotalCaloriesBurnedRecord`, `ActiveCaloriesBurnedRecord`, `DistanceRecord`, `FloorsClimbedRecord`) are read once over the range and summed per local day. Dedup: one origin per day per metric (the one with the most records), and inside it whole-day records (span ≥ 20 h) win over the partial ones, so a source writing both session and day totals is not double counted. A metric the aggregates already cover is never read. | `HcAggregatesTest.raw_fallback_sums_two_step_records_of_the_same_day`, `…keeps_local_days_apart`, `a_whole_day_record_wins_over_the_partial_records_of_the_same_origin`, `only_the_origin_with_the_most_records_of_a_day_is_summed` |
| BUG-5 (old activities without TRIMP) | FIXED | `SyncSummary.minAffectedDay` / `BackfillResult.minAffectedDay` carry the earliest ingested or changed exercise day (first run, incremental changes and every backfill window); `HealthSyncWorker` passes `loadRecomputeDay(outcome, backfillFromDay, today)` to `requestLoadRecompute` instead of always `today`. `LoadRecomputeService.recompute(fromDay)` already widens by 28 days. | `HcSyncServiceTest.first_run_reports_the_oldest_ingested_activity_day_as_min_affected` (activity 40 days old → `today − 40`), `…sync_without_any_activity_reports_no_min_affected_day`; `HealthSyncWorkerTest.load_recompute_starts_at_the_oldest_ingested_activity_day`, `…falls_back_to_today_when_no_activity_was_touched`, `backfill_load_recompute_never_starts_later_than_its_own_start_day` |
| POLISH-1 (warnings on an empty form) | FIXED | `IngredientDraft.hasAnyNutrient()`; `IngredientEditViewModel.warningsFor` returns no warnings until at least one nutrient field carries a value. | `screenshots/fix1_ingredient_no_warnings.png` (new ingredient editor, no warning lines) |
| POLISH-2 (top bars and status-bar insets) | FIXED | Audit result: all 17 screens with their own top bar (templates list/edit, add-food, scan, OCR review, goal edit, planned-session edit, suggestion review, training, event edit, day detail, calendar, activity detail, ingredients list/edit, meal templates, goals, nutrition) already use the `IngredientEditScreen` pattern — `Scaffold(topBar = { TopAppBar(…) }) { innerPadding -> Body(Modifier.fillMaxSize().padding(innerPadding)) }` — and none ignores `innerPadding`. Body, Running PRs, Load, Settings, Integrations, Activities, Today, More have no top bar at all. The real defect was in the shell: `MyHealthNavHost`'s root `Scaffold` padded the `NavHost` with the system-bar insets but never consumed them, so every nested `TopAppBar` applied the status-bar inset a **second** time. Fixed with `Modifier.padding(innerPadding).consumeWindowInsets(innerPadding)`. | `screenshots/fix1_template_insets.png` (title clear of the clock, no double gap), `fix1_template_keyboard.png`, `fix1_dropdown.png`, `fix1_ingredients.png` |
| POLISH-3 (water empty text) | FIXED | `WaterCard`: "No water logged yet today." | code |
| NOTE-3 (water total below 1 l) | FIXED | `waterAmountLabel(ml)` — millilitres under a litre, one decimal from a litre up; used by `waterLabel` and `waterRemainingLabel`. | `screenshots/fix1_water.png` ("750 ml / 2.7 l", "2.0 l left") |

Notes:
- `bash tools/verify.sh` → VERIFY OK, APK 84.6 MB, **579 unit tests**, 0 failures, lint clean.
- NOTE-5 (new, cosmetic): the original POLISH-2 evidence `13_template_filled.png` showed the top bar *overlapping* the clock because the window had panned for the soft keyboard (focused Quantity field far down the page), not because of the inset bug. With the keyboard on a field near the top the bar now stays put (`fix1_template_keyboard.png`). No `windowSoftInputMode` is declared, so Android still picks panning for deeply scrolled fields; switching the activity to `adjustResize` + `imePadding` would need its own pass over every screen and was left out of this batch.

## Session 2 — 2026-09-12 (build after fix batch 1, tests 579)

| Step | Result | Evidence |
|---|---|---|
| Training tab: week board with fixed sessions (synced activities), weekly load bar (Planned/Target/Actual), phase badge "Base", "Generate suggestions" | PASS | 40_training_empty.png |
| Suggestion review: Base phase, target 696 AU, 7 sessions (709 AU) each with rationale lines (phase, budget, recovery 67/100, sport cap), select all / accept / regenerate | PASS | 41_suggestion_review.png |
| Accept selected → Training shows "My plan", Planned 213 AU vs Target 696 vs Actual 505; Day detail 13 Sep lists planned "Strength full 54 min" | PASS | 42_training_after_accept.png |
- POLISH-6 (navigation): re-selecting the "Today" bottom tab restores the tab's saved stack (e.g. Load & Recovery opened from Today's card) instead of showing the dashboard. Make the Today tab pop to `TodayRoute` (no `restoreState` for the home tab, or pop-to-root on reselect) — the other tabs may keep restore semantics.
| Dropdowns after fix: event Type opens (7 options), "Soccer match" selected and saved; Day detail shows "90 min · Soccer Match" | PASS | 45_type_menu.png |
| Adaptive targets: match tomorrow → today's diary shows "Pre match", target 3180 kcal, carbs 500 g (6.5 g/kg), fat 71 g (20 %), protein 135 g (1.8 g/kg); BMR via Katch-McArdle (HC body fat); "Why this target?" explanation lists BMR/TDEE/day type/goal | PASS | 46_diary_prematch.png |
| New event on a past match day (date picker text mode, type Soccer match) → Day detail → "Link activity" sheet proposes the synced "Spiel" activity at 90 % | PASS | 48_link_sheet.png |
| Link accepted: event row shows "Linked to activity", snackbar "Activity linked.", DB `linkedActivityId = 11`, `linkMethod = AUTO_ACCEPTED` | PASS | 49_day6_linked.png |
- POLISH-7 (Day detail): linked-event label reads "Linked to activity #11"; show the activity's title/time (e.g. "Linked: Spiel, 15:00 · 1h 35m") instead of the id.
| Settings "Use wallpaper colours": on → dynamic palette (blue-grey on the emulator), off → green theme again; setting persists | PASS | 50_settings_dynamic_on.png, 51_today_dynamic.png, 52_today_green_again.png |
- BUG-8 (Goals): "Create goal" does nothing for a Race-time goal unless the Distance dropdown is re-selected: `GoalDraft.targetDistanceMeters` defaults to null while the picker displays "5 km", validation adds a DISTANCE error, and `GoalEditScreen` renders errors only for TITLE/TIME/WEIGHT/VALUE — so the failure is silent. **Fix:** initialise the draft with the displayed default (5000 m) whenever type = RACE_TIME (and on type change), render `errors[GoalField.DISTANCE]` under the picker, and show `saveError` prominently. Evidence: 53_goal_filled.png, DB empty until distance re-selected.
| Goal created after selecting distance: "5 km in 19:59 · Current best 20:29 · predicted 27:05 — behind", actions Make primary / Achieved / Abandon | PASS (see POLISH-4: the prediction comes from the slower 10 km effort and contradicts the 20:29 best) | 54_goals_list.png |
| Barcode lookup: enter EAN 3017620422003 → "Look up barcode" → Open Food Facts fills name "Nutella", 539 kcal, 6.3 P / 57.5 C / 30.9 F / 56.3 sugar per 100 g, hint "Filled in from Open Food Facts — check the values." | PASS | 55_barcode_lookup.png |
| Scan screen: camera preview, modes Nutrition label / Barcode, "Capture label" | PASS (opens; capture tested below) | 56_scan_screen.png |
| Capture label with the ML Kit model not yet downloaded → clear message "Text recognition model is still downloading, try again in a moment." + Retry, no crash | PASS | 57_after_capture.png |
- NOTE-6: on the emulator (google_apis image) the unbundled ML Kit text model download via Play Services is slow/uncertain; on the Pixel it downloads once on first use. OCR parsing itself is covered by 34 unit tests over label fixtures; a real-camera OCR run is re-attempted below and on the phone.
- NOTE-6 update: three retries over ~2 min still reported the model downloading → the google_apis emulator image cannot fetch the unbundled ML Kit text model. Plan: add "Scan from photo" (pick an image file → same recognition pipeline) — useful for users too — and verify the OCR path on the emulator with a one-off bundled-model test build against docs/testassets/label_haferflocken_de.png; the phone uses the unbundled model.
| Real Health Connect grant flow for MyHealth (permissions revoked first): Integrations shows ✕ per permission → "Grant permissions" → system sheet "Allow MyHealth to access Health Connect?" → Allow all → Allow → 17 record permissions granted | PASS | 60_integrations_revoked.png, 61_hc_sheet_myhealth.png, 62_integrations_granted.png |
| Health Connect second sheet "Allow additional access" (past data + background) is shown by the same request — the app asks for the history and background permissions correctly | PASS | 63_hc_additional.png |
| After Allow on both sheets: 19/19 health permissions granted (17 record types + history + background) | PASS | 64_integrations_all_granted.png |

## Session 3 — 2026-09-12 (build after P7, tests 620)

| Step | Result | Evidence |
|---|---|---|
| Import screen: pick `run_5k.fit` via the system document picker → "Import finished · 1 parsed · 1 saved · 0 duplicates · 0 errors", history row; DB: RUN_OUTDOOR 5000 m / 1500 s with streams, 2 laps, TRIMP from HR samples, best splits 1 km / 1 mile / 3 km / 5 km (BEST_SPLIT) | PASS | 65_import_screen.png |
| Today's plan card: accepted "Long run · 77 min · 116 AU · Planned" with Done / Plan actions | PASS | 69_after_start.png |
| Today plan card "Done" → session shows "Completed" | PASS | — |
| Running PRs after FIT import: best splits appear (1 km 5:00, 1 mile 8:03, 3 km 15:00 from the 5 km FIT stream) alongside the HC-derived 5 km 20:29 / 10 km 56:30 | PASS | 67_prs_after_fit.png |
| CSV import via the document picker (German-locale Garmin export fixture): "3 parsed · 3 saved · 0 duplicates", rows RUN 10.52 km / CYCLING 30.25 km / STRENGTH, history shows both imports | PASS | 70_csv_import.png |
- NOTE-7: sending a documents-provider URI to the app from the adb shell fails with a SecurityException in the shell (uid 2000 has no grant) — a shell limitation, not an app issue; the share-sheet path is verified on the phone.
| Re-import of the same CSV → "Already imported" (checksum short-circuit), no new rows | PASS | 71_reimport.png |
| Planned-session editor opens from Training "+ Session" (sport, type, intensity, targets, date) | PASS | 72_planned_session_edit.png |
| Manual planned session (Run outdoor / Easy run / Low / 45 min) saved from the editor; weekly Planned AU updates (213 → 151 after the long run moved to Completed and +54 AU easy run) | PASS | 73_training_after_manual.png |
- POLISH-8 (suggestions): the generated week placed "Strength full" on two consecutive days (13/14 Sep) and "Soccer training" on 17/18 Sep; §3.5.3 C11 only spaces STRENGTH_LOWER/HIGH runs/long runs. Add a rule: no identical session type on consecutive days and ≥ 48 h between any two strength sessions. Also: the batch was generated while the 13 Sep event was still an Appointment; after an event's type/date changes, mark the PROPOSED batch stale and show a "Regenerate" hint on Training/Today.
- POLISH-9 (suggestions): `suggestion_batch.status` stays PROPOSED after "Accept selected"; set it to ACCEPTED (or SUPERSEDED) so the review screen and the Today card do not keep offering the same batch.
| Diary item edit: tap quantity "80 g" → dialog (Quantity/Unit) → 100 → Save → row shows "100 g · 373 kcal · 14 g P" (snapshot recomputed, DB kcal 373) | PASS | 76_qty_tap.png, 77_diary_after_edits.png |
| Diary item delete via "Item actions" → Delete: breakfast meal removed, slot shows "Nothing logged.", DB row gone | PASS | 77_diary_after_edits.png |

## Fix batch 2 — 2026-09-12 (build after the fixes + P8.4/P8.5, tests 643)

| Item | Status | Change | Evidence |
|---|---|---|---|
| BUG-8 (race goal could not be created) | FIXED | `GoalDraft.targetDistanceMeters` now defaults to `CanonicalDistances.FIVE_KM` — the value the picker already displayed — and the new `GoalDraft.withType(type)` restores it whenever the type changes back to `RACE_TIME`. `GoalEditScreen` renders `errors[GoalField.DISTANCE]` in the error colour directly under the distance picker, and `saveError`/`loadError` are now `ErrorBanner`s at the top of the form instead of an unstyled line below the fold. | `GoalDraftTest.a_fresh_race_draft_with_a_time_validates_without_touching_the_distance_picker`, `…switching_back_to_a_race_goal_restores_the_default_distance`; emulator: Goals → New goal → title "Sub19 5k", 18/59, **Create goal** → goal saved without ever opening the Distance menu (`screenshots/fix2_goal.png`, `fix2_goal_created.png` shows "5 km in 18:59") |
| POLISH-4 (Riegel/VDOT source effort) | FIXED | `RiegelPredictor.pickSource` now ranks the qualifying efforts (≥ 3 km, ≤ 180 d) by **VDOT** — `maxWithOrNull(compareBy { vdotOf(it) }.thenBy { it.distanceMeters })`, so a tie goes to the longer distance — instead of "largest distance, then fastest time". `RunningPrsViewModel` (predictions + VDOT) and `GoalProgress.predictedSec` (the on-track check) both go through it, so both were fixed by the one change. `pr10` is untouched: `predictSec` still gives 5 k → 10 k = 2501.9 s. | `RiegelPredictorTest.pr13_source_effort_prefers_best_vdot` (20:29 5 km beats 56:30 10 km, and the 10 km prediction drops below 56:00), `…a_stale_effort_is_never_the_source_even_when_it_is_the_best`, `pr10_riegel_5k_to_10k` unchanged; emulator: the 5 km goal now reads "Current best 20:29 · predicted 20:29 — behind" instead of the self-contradicting "predicted 27:05" |
| POLISH-5 (body-fat-only rows) | FIXED | New pure `measurementValueLabel(weightKg, bodyFatPercent)` in `ui/body/BodyUiState.kt`: weight + fat → "78.0 kg, 17.4 % fat", weight only → "78.0 kg", fat only → "Body fat 17.4 %", neither → "—". `BodyScreen.MeasurementRow` delegates to it. | `screenshots/fix2_body.png` — the last-90-days list shows "Body fat 17.4 / 17.5 / 18.0 %" with no leading dash |
| POLISH-7 (linked-event label) | FIXED | New pure `linkedActivityLabel(activityId, activities, zone)` + `shortDuration(seconds)` in `ui/calendar/DayDetailUiState.kt`; `EventsSection`/`eventSubtitle` take the day's `CalendarDay.activities` (already loaded) and resolve the link to title + start time + duration, falling back to `#id` only when the activity is not on that day. | `screenshots/fix2_daydetail.png` — 6 Sep event subtitle reads "Soccer Match · Linked: Spiel, 15:00 · 1h 35m" (was "Linked to activity #11") |
| POLISH-6 (Today tab did not show the dashboard) | FIXED | `navigateToBottomDestination` keeps `popUpTo(TodayRoute) { saveState = true }` and `launchSingleTop`, but uses `restoreState = !isHome` — the Today tab never restores its own saved stack, the other four tabs still do. `MyHealthBottomBar` now also handles a tap on the already-selected tab: `popToBottomRoot(destination)` (`popBackStack(route, inclusive = false)`). ArchitectureTest still green. | emulator: Today → "Recovery" card → Load & Recovery → Calendar → **Today** → dashboard (`screenshots/fix2_today_tab.png`); before the fix the Load screen came back |
| NOTE-6 (Scan from photo) | DONE | `ScanSources` gained `recognizeLabel(uri, cacheDir)` and `scanBarcode(uri)`; `MlKitScanSources` now holds the app `Context` and uses `InputImage.fromFilePath(context, uri)` (EXIF rotation included), with `FrameImages.copyToCache(context, uri, dir)` keeping a copy for the OCR review screen. `ui/` still imports no `com.myhealth.data.*` — the picked `android.net.Uri` crosses the same `di/` seam the `ImageProxy` does. `ScanScreen` offers "From photo" twice (a top-bar `PhotoLibrary` action, always enabled — it needs no permission, so it works even when the camera was denied — and an outlined button next to "Capture label"), in **both** modes. `ScanViewModel.onPhotoPicked(uri)` runs the same pipeline: LABEL → recognise → `NutritionLabelParser` → `DraftStore` → `OcrReviewRoute`; BARCODE → scan → OFF lookup → ingredient editor, with "Enter it manually" on a miss. The model-download message is the existing `ScanMessages.of(MODEL_NOT_READY)` text. | `ScanUiStateTest` (5 cases over the pure `photoStarted/photoCancelled/photoFailed/reviewReady` transitions); emulator: `screenshots/fix2_scan_photo.png` (both actions visible), picker opens, the label PNG is accepted and the pipeline reports **"Text recognition model is still downloading, try again in a moment."** with a Retry (`fix2_scan_model.png`) — i.e. the from-photo path is wired end to end and fails only at ML Kit itself |
| NOTE-6 follow-up | OPEN | Four more attempts over ~2 min, in both LABEL and BARCODE mode, still returned `MODEL_NOT_READY`: the `google_apis` emulator image genuinely cannot fetch either unbundled ML Kit model, exactly as session 2 concluded. OCR parsing stays covered by the 34 label-fixture unit tests; a real recognition run is a **phone-only** check. Cosmetic: in BARCODE mode the shared message still says "Text recognition model", left unchanged so the session-2 evidence string stays stable. | — |
| P8.4 JSON backup (new) | DONE | See `docs/STATUS.md` P8.4. | Emulator: Backup → **Export backup** → picker (Downloads, typed name kept) → SAVE → "1005 rows over 25 tables written", `/sdcard/Download/myhealth-backup-2026-09-12.json` = 1 099 978 B, valid JSON, `schemaVersion 2`, `appVersion 0.1.0`, no settings/credential keys (`fix2_backup_export.png`). Then **Import backup** in **Merge** mode on the same file → "1005 rows … in the file · **0 merged**" (`fix2_backup_import.png`); `run-as com.myhealth sqlite3 databases/myhealth.db` before/after: `activity_session` 42 → 42, `ingredient` 1 → 1, `goal` 2 → 2, `body_measurement` 24 → 24, `meal_log_item` 1 → 1 — no duplicates. |
| P8.5 ingredient FTS (new) | DONE | See `docs/STATUS.md` P8.5. | Installed over the existing v1 database **without clearing app data**: `pragma user_version` 1 → 2, `activity_session` still 42, `sqlite_master` holds `ingredient_fts` + its 4 shadow tables + the 4 `room_fts_content_sync_*` triggers, `select count(*) from ingredient_fts` = 1 and `MATCH 'hafe*'` returns "Haferflocken" (the rebuild worked on pre-existing rows). Logcat shows no migration or SQLite error. In the app: More → Ingredients lists "Haferflocken", and typing "hafe" (≥ 3 chars → the FTS path) still finds it. |

Notes:
- `bash tools/verify.sh` → **VERIFY OK**, APK 86.1 MB, **643 unit tests**, 0 failures, lint clean; `./gradlew :app:compileDebugAndroidTestKotlin` → BUILD SUCCESSFUL.
- NOTE-7 (new, informational): `sync_state` is part of a backup, so a REPLACE restore also restores the Health Connect `changesToken`. On a different device that token is rejected as expired and `HcSyncService` falls back to one full 30-day re-read plus a fresh token (amendment A6), so this is safe — but it is the one row in a backup that is device-specific.

## Session 4 — 2026-09-12 (bundled-ML-Kit test build of the fix-batch-2 tree, on the emulator)

| Step | Result | Evidence |
|---|---|---|
| Scan → "From photo" → Android photo picker → label_haferflocken_de.png → ML Kit text recognition → parser → "Check the scan": Calories 373, Fat 7.0, saturates 1.2, Carbs 58.7, sugars 1.1, Fibre 10.0, Protein 13.5, "Values are per 100 g", per-serving column detected (toggle offered), one field flagged "check this value" | PASS | 82_ocr_review.png, 83_ocr_review_values.png |
| Accept → Ingredient editor prefilled with the recognised values (name left for the user) | PASS | 84_editor_prefilled.png |
- NOTE-8: this run used a one-off build with the bundled `com.google.mlkit:text-recognition` model because the emulator cannot download the unbundled model; the shipped build uses the unbundled Play-Services model (same API), which downloads once on the Pixel. Camera capture on the phone follows the same code path as "From photo".

## Charts — 2026-09-12 (P8.2 + P8.3, build after the chart layer, tests 684)

`bash tools/verify.sh` → **VERIFY OK**, APK 86.1 MB, **684 unit tests**, 0 failures, lint clean.
Installed with `bash tools/emu.sh install` over the existing `emulator-5554` data (no clear, no
reboot) and driven through the UI; every chart below is drawn with Compose `Canvas` only
(amendment A2 — no chart library) and takes all of its colours from `MaterialTheme.colorScheme`.

| Screen | What the screenshot shows | Status | Evidence |
|---|---|---|---|
| Body & Health | 30/90/365 d selector (90 d); **Weight** card with the weight line, the dashed 7-day average and the legend — y ticks 76.0…80.0, x "15 Jun / 29 Jul / 12 Sep"; **Body fat** line 17.0–19.0 % | PASS | `p8_body_chart.png` |
| Body & Health (scrolled) | **Resting heart rate** line 50–60 bpm over the same 90-day x domain; **Sleep (last 14 nights)** bars with per-bar value labels (7.1 … 8.3 h, ≤ 14 bars so the labels are drawn) and `30/8 · 5/9 · 12/9` x labels; the range-aware "Last 90 days" list below | PASS | `p8_body_chart_2.png` |
| Load & Recovery | **Acute vs chronic load**: ATL (primary) and CTL (tertiary) lines with the legend, ticks 50/100/150 AU; the ATL/CTL/ACWR/TSB tiles above are unchanged | PASS | `p8_load_chart.png` |
| Load & Recovery (scrolled) | **ACWR** with the §3.2.3 zones shaded — 0.8–1.3 green tint, 1.3–1.5 amber (the > 1.5 error band is off-scale because the data tops out at 1.4); **Daily TRIMP** 28 bars (no value labels above 14 bars, today highlighted); **Recovery score** line 60–100 | PASS | `p8_load_chart_2.png` |
| Activity detail (run "Lauf", 12.36 km) | **Heart rate over time** 120–180 bpm against elapsed minutes (`0:00 / 34:00 / 68:00`); the existing HR min/avg/max + time-in-zone table is untouched below it | PASS | `p8_activity_chart.png` |
| Activity detail (scrolled) | **Pace over time** on an inverted axis — 5:00 at the top, 6:20 at the bottom, so a faster kilometre sits higher | PASS | `p8_activity_chart_2.png` |
| Running PRs | **PR progression**: one line per canonical distance with ≥ 2 efforts (here 5 km: 25:00 → 20:29 → 20:43), y labels formatted as race times, x as months, legend forced on so the single distance is named | PASS | `p8_prs_chart.png` |

Fixes made while reviewing the screenshots:

- Body weight/body-fat lines were a dot cloud: both quantities are *sampled* irregularly, so a day
  without a reading is "not weighed", not "nothing happened". The daily grid is still used to
  compute the 7-day average, then `dropGaps()` joins the sampled points. Sensor streams (HR, pace)
  keep the gap semantics.
- A Health Connect run rendered "Speed over time" instead of pace: HC exercise sessions carry
  `speedMps` but no cumulative distance stream, so `paceSeriesFromSpeed` was added as the fallback
  (FIT imports still use the distance channel).
- The single-series PR chart had no legend and the title does not name the distance →
  `LineChartCard(alwaysShowLegend = true)`.

Notes:

- NOTE-9: PLAN P8.3 says "ATL/CTL lines with ACWR zone shading". ATL/CTL are in AU (50–150 here)
  and ACWR is a ratio around 1.0, so shading ACWR zones on the AU axis would be meaningless; the
  zones are shaded on their own ACWR chart instead and ATL/CTL get a plain two-series chart.
- NOTE-10: PR progression plots absolute finishing time, as the plan specifies. With efforts at
  very different distances (1 km and a marathon) the short-distance line would flatten against the
  bottom of the axis; a per-distance y axis or a pace axis would be the fix if that ever happens.
- Navigation note for future runtime checks: the More tab remembers its own back stack, so tapping
  "More" while already on a More sub-screen does nothing. Press Back once to return to the More hub
  (this is safe — Body/Load/PRs are nested destinations, not the app root).

## Polish batch — 2026-09-12 (P8.1 + P8.6 + P8.7 + POLISH-8 + POLISH-9)

`bash tools/verify.sh` → **VERIFY OK**: **691 unit tests**, 0 failures, lint clean with
`HardcodedText` as an **error**, and `:app:assembleRelease` now part of the script.

| Artifact | Size |
|---|---|
| Debug APK (unminified) | **87.2 MB** (91,440,511 bytes) |
| Release APK (R8 + `shrinkResources`) | **13.9 MB** (14,569,988 bytes) — **6.3× smaller**, well under the ≤ 40 MB budget of §6.6 |

### POLISH-8 — `C13` and the staleness hint

- `Constraints.C13` (`domain/engine/suggest/Constraints.kt`): no identical `SessionType` on two
  adjacent grid days, and ≥ 48 h between any two `STRENGTH_*` sessions (the three variants are
  spaced as one family, so `STRENGTH_UPPER` the day after `STRENGTH_FULL` is now rejected). Both
  halves look at **every** grid item, fixed or suggested, so a soccer training already on the
  calendar blocks a suggested one the next day — the exact 13/14 and 17/18 Sep case from session 3.
  `MOBILITY` is exempt from the same-type half, because post-pass 7c deliberately puts one on every
  rest day (`sug18`).
- Tests `c13_no_same_session_type_on_consecutive_days` and `c13b_strength_sessions_48h_apart` in
  `ConstraintsTest`. **No `sug01…sug20` fixture needed changing** — C13 only removes candidates the
  greedy loop could have chosen, and every named case still asserts the same outcome.
- Staleness: `SuggestionRepository` gained `markProposedStale()` / `observeStale()`.
  `RoomCalendarRepository` takes an `onPlanChanged: suspend () -> Unit` hook, fired from
  `upsertEvent` / `deleteEvent` / `upsertOverride` / `deleteOverride`; `AppGraph` wires it to
  `suggestionRepo.markProposedStale()`. The flag is `AppSettings.suggestionsStale` in DataStore —
  **no schema change**, as the brief requires — and `observeStale()` only reports `true` while the
  latest batch is still `PROPOSED`. `generate()` and a completed review clear it.
- Tests: `RoomCalendarRepositoryTest.every_event_write_notifies_the_plan_changed_hook`,
  `RoomSuggestionRepositoryTest.a_calendar_change_marks_an_open_proposed_batch_stale_until_it_is_regenerated`
  and `…marking_stale_does_nothing_when_no_batch_is_awaiting_review`.

### POLISH-9 — the batch closes when the review is saved

`RoomSuggestionRepository.closeBatches()` runs after `accept`/`reject`: a batch with at least one
`ACCEPTED` session becomes `ACCEPTED`, one whose sessions are all `REJECTED` becomes `REJECTED`, and
a partly-reviewed batch (still holding `PROPOSED` rows) is left alone so the rest can be reviewed.
`observeLatestBatch()` still returns the row — the Training phase badge reads it — but
`SuggestionReviewViewModel` and `TodayViewModel` now only propose from a `PROPOSED` batch. Tests:
`a_partly_reviewed_batch_stays_proposed_until_every_session_is_decided`,
`a_batch_whose_sessions_are_all_rejected_becomes_rejected`, and the extended
`accept_copies_suggestions_into_planned_sessions_and_creates_the_default_plan`.

### Release smoke test (R8 build on `emulator-5554`, existing data kept)

`adb install -r -g app/build/outputs/apk/release/app-release.apk` over the debug install (same debug
signature ⇒ no data loss, no `pm clear`, no reboot).

| Step | Result | Evidence |
|---|---|---|
| Launch → Today: nutrition card, plan card, recovery 70/100, "Last synced 19:38" | PASS | `screenshots/rel_today.png` |
| Calendar (month grid), Nutrition (diary with meals + water 750 ml / 3.2 l), Training (week board, 7–13 Sep, 505/696 AU) | PASS | — |
| More → Activities (6 rows, source badges) → activity "Lauf" detail: 12.36 km, HR/pace charts, laps | PASS | `screenshots/rel_activity.png` |
| More → Ingredients, Import (history with 2 records), Backup, Integrations (all HC permissions listed) | PASS | — |
| Integrations → **Sync now**: `HealthSyncWorker` → SUCCESS, `LoadRecomputeWorker` → SUCCESS, every channel timestamp advanced to 21:51 | PASS | logcat `WM-WorkerWrapper` |
| Training → Generate suggestions → review (6 sessions, 395 AU, "In season · target 600 AU") | PASS | `screenshots/rel_suggestions.png` |
| **C13 in the field**: the generated week places Soccer training on Wed **and Fri** with a mobility day between them — previously two in a row | PASS | `rel_suggestions.png` |
| **POLISH-8 in the field**: creating a calendar event under the open batch → "Calendar changed — regenerate" on both Training and the Today card; "Regenerate" reruns the suggester and clears it | PASS | `screenshots/rel_today_stale.png`, `rel_training_stale.png` |
| **POLISH-9 in the field**: "Accept selected" → the Today card stops offering the batch and shows the accepted sessions instead, while the Training phase badge still reads "In season" | PASS | — |
| `adb logcat -d \| grep -E "FATAL\|AndroidRuntime"` after the whole walkthrough | **clean** — 0 `FATAL`, no `ClassNotFoundException` / `NoSuchMethodException` / serialization errors | — |

No keep rule had to be added after the fact: `app/proguard-rules.pro` was written up front for
kotlinx-serialization (`@Serializable` classes + `$$serializer` + `Companion.serializer()`), Room
(`*_Impl`, entities, converters), the type-safe navigation routes, DataStore's protobuf,
`ListenableWorker` subclasses, Health Connect, ML Kit / Play Services, CameraX, `com.garmin.fit.**`
and OkHttp. The debug APK was reinstalled afterwards (`bash tools/emu.sh install`).

### P8.1 — string extraction

`res/values/strings.xml` went from **43** to **778** `<string>` entries; `HardcodedText` is now
`error` in `app/lint.xml` and `lintDebug` is clean. ViewModel/UiState text that used to be a `String`
now travels as `com.myhealth.ui.common.UiMessage` (`@StringRes` + args) and is resolved in the
composable, so `domain/` stays Android-free and no ViewModel holds a `Context`.

**Deliberately left as literals (68 strings, listed in the run's `skipped_*.txt` notes):** the pure,
non-`@Composable` label helpers that unit tests assert verbatim — `goalTypeLabel`/`goalStatusLabel`/
`goalHeadline` (`GoalDraftTest`), `permissionLabel`/`syncChannelLabel` (`IntegrationsUiStateTest`),
`flagExplanation`/`recoveryBandLabel` (`LoadUiStateTest`), `distanceLabel` (`RunningPrsUiStateTest`),
`validatePlannedSession` (`PlannedSessionDraftTest`), `validateTemplate` (`MealTemplateDraftTest`),
`OcrReviewUiState.basisNote` (`OcrReviewUiStateTest`), the `targetProgressRows` macro labels and
`formatSleepDuration` (`DayDetailUiStateTest`), and the number-glued unit suffixes (`" min"`,
`" AU"`, `" g"`) and `" · "` separators inside those same helpers. Moving them would mean rewriting
the §3/§4 named tests that pin their exact output, which R8 (rule R8 of §0.1) forbids doing
casually. They are single-language English constants in an English-only app, so nothing is lost
today; a follow-up could convert them together with their tests.

### P8.6 — visual polish

- New `ui/common/Dimens.kt` (`SCREEN_PADDING` 16 dp, `CARD_CORNER_RADIUS` 12 dp) and
  `ui/common/LoadingBox.kt`; every screen root list now uses `PaddingValues(SCREEN_PADDING)` and
  every card surface `RoundedCornerShape(CARD_CORNER_RADIUS)`.
- `PullToRefreshBox` (M3 1.4) on **Today** and **Activities**, both triggering `syncNow()`; the
  spinner is driven by the real `SyncScheduler.observeState()`, and Activities gained a retryable
  `ErrorBanner` for a failed sync plus a `LoadingBox` for its first load.
- Body & Health's history list gained the standard icon + title + message + action empty state
  (`EmptyState`), and its "Last N days" header/empty text now follows the 30/90/365-day selector
  instead of being hard-coded to 90.
- Launcher icon: the P0 flat square is replaced by a diagonal green gradient background, a white
  heart carrying a pulse trace with a leaf on its top-right lobe (`ic_launcher_foreground.xml`), and
  a dedicated `ic_launcher_monochrome.xml` (heart + pulse as one `evenOdd` path) for themed icons.
  All vector, no external assets.
- `TodayScreen.kt` (415 lines) and `ActivityDetailScreen.kt` (402) were split by moving their
  `@Preview`s into `TodayScreenPreviews.kt` / `ActivityDetailPreviews.kt`, back inside R10's budget.

Notes:

- NOTE-11: lint now reports 21 `PluralsCandidate` warnings against the new strings (e.g. "%1$d
  logged"). English-only app, so they stay warnings; converting them to `<plurals>` is a tidy-up for
  whenever a second language appears.
- NOTE-12: P8.8 (Glance home-screen widget) is **skipped** — it is the one task PLAN §5 marks
  optional, and Glance would need a dependency addition that R4/R5 forbid outside a task that lists it.

## Session 5 — 2026-09-12 (final debug build e461e57, tests 691)

| Step | Result | Evidence |
|---|---|---|
| Regression pass: Today (plan card with completed + planned sessions, recovery 70, targets), Training (phase "In season" now that the match event is typed, planned/target/actual bar), Nutrition diary, Ingredients (empty query lists the ingredient — BUG-2 fixed), Body charts; no crashes in logcat | PASS | 90_final_*.png |

## Instrumented tests (PLAN P10.2)

`JAVA_HOME=~/jdk/current ANDROID_HOME=~/android-sdk ./gradlew :app:connectedDebugAndroidTest`
on `emulator-5554` (Android 15, `myhealth_api35`): **10/10 pass** —
`MyHealthDatabaseTest` (4), `BottomNavTest`, `CalendarEventTest`, `IngredientTest`,
`MealTemplateAndDiaryTest`, `OnboardingFlowTest`, `SettingsPersistenceTest`. Two consecutive full
runs were both 10/10 green (no flakiness observed); no test was `@Ignore`d.

Starting state was 6/10 (the same 4 UI tests failing); all 4 failures were test-code bugs, not
product bugs, and were fixed without touching production code except reading the two pre-existing
`Modifier.testTag("settings_dynamic_color_switch")` uses already in `SettingsSections.kt` — no new
`testTag` was added anywhere.

- **`IngredientTest`** ("Calories" not found) and **`MealTemplateAndDiaryTest`** ("Save template"
  not found): both screens' energy/macro fields and the Save button sit in `item {}`s below the
  fold of a `LazyColumn`. A `LazyColumn` only composes items near the viewport, so a plain
  `performScrollTo()` fails outright (there is no node yet to scroll to) — fixed with
  `onNode(hasScrollAction()).performScrollToNode(hasText(label))`, which scrolls the list by index
  until the target composes, then acts on it. `MealTemplateAndDiaryTest` additionally needed
  `Espresso.closeSoftKeyboard()` after the quantity-field edit so the keyboard did not intercept
  the Save click.
- **`OnboardingFlowTest`** ("found 2 nodes that satisfy SetText"): `onNode(hasSetTextAction())`
  matched both the read-only birth-date `OutlinedTextField` (which still carries a SetText
  semantics action despite `readOnly = true`) and the `DatePickerDialog`'s real text-input field.
  Scoped the matcher to `hasSetTextAction() and hasAnyAncestor(isDialog())`.
- **`SettingsPersistenceTest`** (10 s `waitUntil` timeout): a `printToLog` semantics-tree dump
  showed two separate issues. First, the same below-the-fold problem — `ProfileSection` (the
  Settings `LazyColumn`'s first item) is by itself taller than the viewport, so
  `AppPreferencesSection` (item 2, holding the "Use wallpaper colours" switch) never composes from
  a plain `waitUntilTextExists` on its label; fixed with the same `performScrollToNode` pattern,
  scrolling to the switch's existing test tag. Second, the dump showed that after
  `scenario.recreate()` (which restores the NavController's saved back stack straight back onto
  Settings, not Today), re-tapping the bottom-nav "More" tab is a **dead click**: Settings is
  reached from the More list by a plain `navigate()` push, not through the bottom bar's own
  save/restore machinery, and tapping "More" from it left the app on Settings with no navigation
  at all. The fix does not re-navigate after the second `recreate()` — it scrolls and asserts on
  the screen that is already showing.
- **`MealTemplateAndDiaryTest`** (diary total right, but the logged item never found): the new
  template is created with no default slot, so `defaultSlotFor` falls back to `MealSlot.LUNCH`;
  the diary's `LazyColumn` renders "Breakfast" first, so the logged item row is further down the
  list than the fold — same `performScrollToNode` fix, applied to the diary screen.

## Scope decision — P9 (direct Garmin Connect client)

Not built, deliberately. Garmin offers no personal API; the only route is the reverse-engineered SSO flow used by community Python libraries, which Garmin broke in March 2026 and can break again at any time, and which requires storing the Garmin password on the device. Everything the owner asked for (activities, HR, sleep, steps, calories, weight, body fat, HRV, VO2max) arrives through Health Connect, which is the supported path. The isolation seam (`GarminMetricsProvider`, PLAN P9.1) remains available should Garmin's Body Battery / stress / training readiness ever be wanted; it would be a self-contained, optional, default-off module.

## Session 6 — 2026-09-13 (P11 cycle tracker, build ef19d44, 734 unit + 12 instrumented tests)

| Step | Result | Evidence |
|---|---|---|
| Onboarding as FEMALE: step 3 shows "Track menstrual cycle" (default on) | PASS | c01_onboarding_step3.png |
| Cycle screen (More → Cycle): empty state → "Log period start" (date picker, default today) → status "Menstrual · Day 1 of ~28 · Next period in 28 days (11 Oct) · Ovulation ~27 Sep · Fertile window 22–28 Sep · default-cycle confidence note", forecast of 6 cycles with period ranges and ovulation dates, history row "13 Sep 2026 · Ongoing", "Period ended" action | PASS | c03_cycle_empty.png, c05_cycle_status.png, c06_cycle_forecast.png |
| Today: "Cycle" card (Menstrual · Day 1 of ~28 · next period) | PASS | c07_today_scrolled.png |
| Calendar: period days 13–17 (red), fertile window 22–24/28 (olive), ovulation window 25–27 (rings), predicted next period 11 Oct (light red) | PASS | c08_calendar_markers.png |
- POLISH-10 (suggestions, new users): right after the first sync (before `daily_load` exists) or for a user with no history, `weeklyTarget = min(ctl·7·factor, max(lastWeek·1.25, 150))` is 0, so the generated week contains only mobility. Use a starter target (150 AU) when CTL < 5 and there is no last-week load, and label it "starter week" in the rationale.
  - FIXED (2026-09-13): `Periodization.isStarterWeek(ctl, lastWeekActual)` (CTL < 5 AU and no load logged last week) now makes `weeklyTarget` return `STARTER_TARGET_AU` = 150 instead of the near-zero `ctl*7*factor` value; ACWR/recovery multipliers still apply afterwards (never raises the budget, only lowers it, per R13). `PeriodizationResult.isStarterWeek` carries the flag to `SuggestionEngine`, which adds a new `Rationale.RULE_STARTER_WEEK` ("Starter week: no training history yet, so this is a gentle first week.") entry to every session `forSession` places and to rest-day `forMobility` fillers. Files: `domain/engine/suggest/{Periodization,Rationale,SuggestionEngine}.kt`. Tests: `PeriodizationTest.per04_starter_target_when_no_history` (old `per04` acwr test renamed to `per07`, `per05`/`per06` unchanged), `SuggestionEngineTest.sug26_starter_week_rationale`; `sug09`–`sug20`/`per01`–`per03` untouched and still green. **Discrepancy**: the task note asked for the STARTER_WEEK text to be "a string resource in the UI layer like the other rule ids", pointing at how `CYCLE_*` ids were wired in `ui/training`/`strings.xml` — but no such wiring exists anywhere in the codebase; every rule id's display text (including all `CYCLE_*` ones) is a literal string built in `domain/engine/suggest/Rationale.kt`, and `ui/common/RationaleList.kt` renders `entry.text` verbatim with no ruleId→string-resource lookup. Implemented STARTER_WEEK the same way as every existing rule id (domain-literal text) rather than inventing a new, one-off UI wiring mechanism; `res/values/strings.xml` and `ui/training/*` were left untouched.
| Cycle-aware suggestions (after sync, CTL 83, target 473 AU, 11 sessions): rationale lines per day — day 1/2 "keeping the intensity moderate for the first two days", day 3–5 "quality work is fine now, rated a little more cautiously", follicular "intervals and strength work are usually best tolerated now", each with "Based on a default 28-day cycle — log your period to improve this" | PASS | c13_review_1.png … c13_review_6.png |
- POLISH-11 (onboarding): the "sessions / week" fields default to 0, so a user who skips them gets sport caps of 0 and the suggester can only propose cross-training/mobility. Default to Run 2 / Strength 2 / Soccer 1 and treat 0 as "no cap" only when all three are 0.
  - FIXED (2026-09-13): `OnboardingDraft.sessionsPerWeek` now defaults to `DEFAULT_SESSIONS_PER_WEEK` (Run 2 / Strength 2 / Soccer 1) instead of all-zero; no `OnboardingViewModel`/`OnboardingScreen` change was needed since both already flow the draft's default through unchanged. `SportPreferences.capsOf` (`domain/engine/suggest/DayPlan.kt`) now returns `emptyMap()` (= no cap on any sport, C10 never fires) when the parsed caps map is non-empty and every value is 0; a single sport left at 0 alongside a non-zero one is still a real, deliberate cap on just that sport. Settings/an explicitly-cleared profile keep whatever the user set (the all-zero case simply means "no caps configured", handled identically for onboarding and settings since both go through the same `capsOf`). Files: `ui/onboarding/OnboardingDraft.kt`, `domain/engine/suggest/DayPlan.kt`. Tests: `ConstraintsTest.c10b_all_zero_caps_means_no_cap`, `OnboardingDraftTest` (new file: `default_draft_has_the_starter_sessions_per_week`, `default_sessions_per_week_encodes_to_non_zero_caps`, `a_user_can_still_choose_all_zero_explicitly`); existing `c10_per_sport_weekly_cap_counts_fixed_sessions` and `sport_preferences_decode_caps_and_long_run_weekday` still green.
  - `bash tools/verify.sh` (both POLISH-10 and POLISH-11 together): `BUILD SUCCESSFUL`, `Unit tests : 740 executed, 0 failures, 0 errors, 0 skipped`, lint clean, `VERIFY OK`, exit 0.
| Settings shows "Track menstrual cycle" switch; Cycle → "Period ended" (date dialog, default today) sets `periodEndDay` on the current entry (DB: start 20709, end 20709) | PASS | c14_settings_cycle_switch.png, c15_period_ended.png |
- POLISH-10 → FIXED: `Periodization` uses a 150 AU starter target when CTL < 5 and the previous week has no load; every suggested session then carries the `STARTER_WEEK` rationale ("Starter week: no training history yet, so this is a gentle first week"). Tests `per04_starter_target_when_no_history`, `sug26_starter_week_rationale`.
- POLISH-11 → FIXED: onboarding defaults Run 2 / Strength 2 / Soccer 1 sessions per week; C10 treats all-zero caps as "no cap" (`c10b_all_zero_caps_means_no_cap`, `OnboardingDraftTest`).

## Session 7 — 2026-09-13 (Pixel 7a, Android 17 / SDK 37, Garmin Connect 5.29)

| Step | Result | Evidence |
|---|---|---|
| Install debug build on the Pixel 7a; first launch shows onboarding, no crash | PASS | (screenshot kept private) |
- NOTE-12 (Garmin → Health Connect on the phone): Garmin Connect 5.29 declares exactly these Health Connect write permissions: ACTIVE_CALORIES_BURNED, BODY_FAT, DISTANCE, ELEVATION_GAINED, EXERCISE, FLOORS_CLIMBED, HEART_RATE, RESTING_HEART_RATE, SLEEP, SPEED, STEPS, TOTAL_CALORIES_BURNED, WEIGHT — and no HRV, VO2max, SpO2 or respiratory rate. None were granted before this session (Health Connect sync not enabled in Garmin Connect yet). Consequence: the recovery score runs on sleep + resting HR + load (HRV component absent, weights renormalise); VO2max stays the app's own VDOT estimate.
| Onboarding completed by the owner; all 19 Health Connect permissions granted through the system sheets during onboarding; dark theme renders with green accents | PASS | (screenshot kept private) |
| Sync now + backfill (to 2023-08-01): the owner's recent activities with HR streams and TRIMP, about a week of daily summaries (total kcal, resting HR, steps), sleep sessions with stages, a recovery score, ACWR flagged INSUFFICIENT_HISTORY | PASS | (screenshot kept private) |
- NOTE-13 (Garmin data depth): Garmin Connect only wrote the last ~7 days into Health Connect after the integration was enabled (nothing older exists in HC despite the 365-day backfill). Older history, PRs and CTL warm-up therefore come from a Garmin export (FIT/CSV/ZIP) via More → Import, as documented in DELIVERY.md.
- BUG-9 (sync status): `sync_state.lastError` ("Health Connect permission denied", from the worker run before permissions were granted) is not cleared by a later successful sync, so Today shows "Sync failed: Health Connect permission denied" although all four channels synced at 13:20. **Fix:** `recordSuccess` clears `lastError`/`lastErrorAtMillis`; Today's banner only shows an error whose timestamp is newer than the last success.
- POLISH-12 (formatting): decimals are locale-formatted in some places (a decimal comma on a German phone) and dot-formatted in others (ratios, kg values); pick one convention (locale-aware everywhere).
  - FIXED (2026-09-13): added `ui/common/Numbers.kt` — `fmtDecimal`/`fmtInt`/`fmtKg`/`fmtKm`/`fmtPercent` built on `NumberFormat`/`DecimalFormat` for `Locale.getDefault()`, and `parseDecimal` which accepts either "," or "." as the decimal separator. `NumberField` (the only place `ui/**` parses user-typed numeric text) now displays through `fmtDecimal` and parses through `parseDecimal`, and now also accepts a typed "," while entering a number — every quantity/goal/plan/ingredient/body/onboarding numeric field is built on `NumberField`, so this one change covers all of them. Replaced every ad-hoc `"%.Nf".format(...)` / `String.format(Locale.US, ...)` / round-then-`toString()` decimal display across `ui/**` (today, load, activities, running, body, calendar, nutrition, ingredients, camera OCR review, training, calendar link sheet, chart axis labels in `ui/common/charts`) with the new helpers — 59 call sites in 22 files. Left untouched, deliberately: clock-style `%02d:%02d`/`%d:%02d` padding (pace, duration, time-of-day — same in every locale, not a decimal-separator bug) and `stringResource(...)`/`String.format(raceOptionFormat, ...)` calls, which Android already formats through the device's configured locale. `domain/` formats nothing and was not touched. Where a named unit test asserted a formatted string verbatim without pinning a locale (`TodayUiStateTest`, `NutritionUiStateTest`, `RunningPrsUiStateTest`), added `@Before Locale.setDefault(Locale.US)` / `@After` restore so the assertions stay locale-independent. Files: `ui/common/Numbers.kt` (new), `ui/common/NumberField.kt`, plus the 22 files above. Tests: `NumbersTest` (new: `fmtDecimal`/`fmtInt` under `Locale.GERMANY` vs `Locale.US`, `fmtKg`/`fmtKm`/`fmtPercent`, `parseDecimal` comma/dot equivalence and garbage → `null`).
- POLISH-13 (load): the backfill created ~1 170 `daily_load` rows of zeros back to 2023 (recompute from `fromDay − 28`); start the series at the first activity day instead.
  - FIXED (2026-09-13): `ActivityDao.getFirstActivityDay()` (new: `SELECT MIN(day) FROM activity_session`) gives the earliest activity day, or `null` when there are none. `LoadRecomputeService.recomputeInternal` now clamps the series window to `max(fromDay − 28, firstActivityDay)`, and with no activities at all writes no rows and returns early (after clearing any stale cache). Every recompute also calls the new `LoadRepository.deleteBefore(firstActivityDay)` (→ `LoadDao.deleteBefore`: `DELETE FROM daily_load WHERE day < :beforeDay`) to prune any `daily_load` rows left over from before the first activity. The EWMA seed is unchanged (`ATL_{-1} = CTL_{-1} = 0`), since it now simply seeds at the (later) clamped start day instead of at `fromDay − 28`. Files: `data/db/dao/ActivityDao.kt`, `data/db/dao/LoadDao.kt`, `domain/repository/LoadRepository.kt`, `data/repository/RoomLoadRepository.kt`, `data/repository/LoadRecomputeService.kt`, plus test doubles `data/repository/FakeActivityDao.kt` and `data/repository/LoadRecomputeFakes.kt`. Tests (new, in `LoadRecomputeTest`): `backfill_starts_at_the_first_activity_day_not_fromDay_minus_28`, `rows_before_the_first_activity_are_deleted_on_recompute`, `no_activities_means_no_rows`; existing `load09`…`load17` and `four_hundred_days_of_history_recomputes_quickly` still green.
| Activity detail with real Garmin data: duration · distance · avg / max HR · TRIMP (HR samples) · calories, HR-over-time and speed-over-time charts, link-to-event card | PASS | (screenshot kept private) |
| Load & Recovery: ATL / CTL / ACWR (Caution) / TSB, monotony, strain, charts, flags (insufficient history, sleep debt) | PASS | (screenshot kept private) |
| Body: latest weight and the distance to the weight goal, weight/body-fat/resting-HR sections | PASS | (screenshot kept private) |
| Calendar + Day detail of a training day: activity dot, sleep with stages, TRIMP | PASS | (screenshot kept private) |
| Nutrition: kcal and macro targets; "Why this target?" = BMR (Mifflin, from the profile's weight, height and age), TDEE estimated, REST, the weight goal's daily deficit, "raised to the safety floor", protein g/kg (deficit) | PASS | (screenshot kept private) |
| Training → Generate suggestions on one week of history: Base, a low target from the short history, long run + mobility fillers with rationale | PASS | (screenshot kept private) |
| Scan → From photo on the phone (unbundled ML Kit model, Google Photos picker): all label values recognised (373 kcal / 1560 kJ, fat 7.0, saturates 1.2, carbs 58.7, sugars 1.1, fibre 10.0, protein 13.5, salt 0.02), per-100 g basis, per-serving column detected; camera permission prompt handled | PASS | (screenshot kept private) |
| BUG-9 fix verified on the phone: after reinstall + Sync now, no "Sync failed" banner, `sync_state.lastError` null on all channels | PASS | (screenshot kept private) |
| Final state: release build (14 MB, R8) installed over the debug data; Today renders with the synced data | PASS | (screenshot kept private) |
- BUG-10 (reported by the owner on the phone, 2026-09-13): accepting a generated week adds the new sessions on top of the existing **unlocked** planned sessions in the same horizon instead of replacing them. Expected: on accept, every unlocked `PLANNED` session within the batch horizon (`horizonStartDay..horizonEndDay`) is deleted (`PlanRepository.getReplaceableSessions` exists for this and is unused), locked sessions and COMPLETED/SKIPPED sessions stay; the review screen states how many unlocked sessions will be replaced; generation seeds the grid only with locked sessions (already the case) so the proposal does not double-count unlocked ones.
- BUG-10 → FIXED: `RoomSuggestionRepository.accept` now deletes every unlocked `PLANNED` session inside each accepted batch's horizon before inserting the accepted suggestions (locked, COMPLETED and SKIPPED sessions and sessions outside the horizon stay); the review header states "N unlocked planned session(s) will be replaced" via `countReplaceableSessions`. Test `accept_replaces_unlocked_planned_sessions_in_the_horizon_and_keeps_locked_and_completed_ones`.
| The owner's Garmin activities CSV (German headers, English numbers, a few weeks of rows) imported on the phone: every row parsed and saved, 0 duplicates | FAIL → BUG-11 | (screenshot kept private) |
- BUG-11 (CSV import, real export): the owner's `Activities.csv` has **German column headers** ("Aktivitätstyp, Datum, Distanz, Kalorien, Zeit, Ø Herzfrequenz, …") but **English number formatting** ("7.20", "1,057", "00:09:53.7"). `GarminCsvParser` chose the German number format (decimal comma / thousands dot), so every distance was read ×100 and calories with a thousands comma were mis-parsed. Consequences: the sessions already synced from Health Connect were not matched by `ActivityMatcher` (distance predicate) and now exist twice; a 5 km run produced no 5 km PR. **Fix:** decide the number format from the numeric cells, not the headers: count cells matching `^\d{1,3}(,\d{3})*(\.\d+)?$` (English) vs `^\d{1,3}(\.\d{3})*(,\d+)?$` (German) across distance/calories/speed columns; ties → the decimal separator that appears with 1–2 trailing digits; fractional-second durations (`hh:mm:ss.s`) must parse. Add a fixture `garmin_de_headers_en_numbers.csv` built from the owner's header line + representative rows and tests `csv09_german_headers_with_english_numbers`, `csv10_fractional_seconds_duration`.
- FEATURE (needed to recover from BUG-11 on the phone, and useful in general): **Undo import** — `activity_source_record.importRecordId` (DB v4, `MIGRATION_3_4`), set by `ImportService`; Import history rows get "Undo" which removes that import's source records (re-merging or deleting the canonical activities via `ActivityIngestor.removeSourceRecord`), deletes the `import_record` (so the file can be re-imported) and triggers a load recompute.
- BUG-11 → FIXED: the CSV number format is now voted on by the numeric cells (distance, calories, speeds, elevation; `1,057` → English, `1.057` → German, integers abstain, ties → header guess); `Ø Herzfrequenz`/`Maximale Herzfrequenz` aliases added (avg HR was silently dropped before); sport lookup is case/diacritic/punctuation-insensitive and covers all German activity types of the owner's export. The owner's small set now parses with the right sport types and distances; the full export parses with 0 errors. Tests `csv09…csv12`.
- FEATURE Undo import → DONE: DB v4 (`activity_source_record.importRecordId`, `MIGRATION_3_4`), `ImportService` stamps every arrival, `RoomImportRepository.undo()` removes the import's source records (re-merging or deleting canonical rows), forgets the file's checksum and triggers a load recompute; Import history rows have an "Undo" action with confirmation. Tests: `ImportServiceTest.undo_…`, `RoomImportRepositoryTest` (3), `migration_3_to_4`.

## Session 8 — 2026-09-13 (Pixel 7a, v1.0.2)
| Step | Result | Evidence |
|---|---|---|
| Import history → "More actions" → Undo import → confirmation ("Removes the N activities that only came from this file; activities also known from Health Connect are kept…") → history empty, CSV-only duplicates removed | PASS | (screenshot kept private) |
- POLISH-14 (Import screen): after an undo the previous "Import finished · N parsed…" result card stays visible until the next import; clear it when its import is undone.
| Full CSV import on the phone: every row parsed and saved, 0 duplicates · 0 errors; sessions also known from Health Connect merged (rows carry HC + CSV, correct distances); Running PRs filled from the imported history | PASS | (screenshot kept private) |
- BUG-12 (Undo import, migration gap): the rows of the first (mis-parsed) import were ingested before DB v4, so their `activity_source_record.importRecordId` is null; "Undo import" reported "removes N activities" but deleted nothing (it selects by `importRecordId`) and only removed the `import_record` row. The ×100-distance rows remain as CSV-only duplicates. **Fix (hotfix 1.0.3 from tag v1.0.2):** (a) `RoomImportRepository.undo` falls back, when an import has no stamped records, to source records of the import's source kind received within ±15 min of `importedAtMillis` — and stamps them first; (b) Settings → Advanced → "Remove orphaned import data" deletes source records of kind CSV_IMPORT/FIT_IMPORT with `importRecordId == null` and no matching `import_record`, re-merging/deleting canonical rows and recomputing load; (c) tests for both. Evidence: (screenshot kept private) (×100-distance rows next to the correct rows after the undo).
- BUG-12 → FIXED (hotfix 1.0.3, this worktree): `RoomImportRepository.undo` now falls back to `ActivityDao.getUnstampedSourceRecordsInWindow` (unstamped records of the import's source kind — `GARMIN_CSV`→`CSV_IMPORT`, `FIT_FILE`/`GARMIN_ZIP`→`FIT_IMPORT` — within ±15 min of `importedAtMillis`) when the primary `importRecordId` lookup finds nothing, stamping the matches before removing them; `ImportRepository.removeOrphanedImportData()` sweeps every remaining unstamped `CSV_IMPORT`/`FIT_IMPORT` record regardless of when it arrived, via the new Settings → Advanced → "Remove orphaned import data" action (confirmation dialog, then a result snackbar). Tests: `RoomImportRepositoryTest.undo_falls_back_to_unstamped_records_in_the_import_time_window`, `undo_fallback_ignores_records_outside_the_window_and_other_sources`, `remove_orphaned_import_data_removes_only_unstamped_file_imports`.

## Session 9 — 2026-09-13 (Pixel 7a, v1.0.3 hotfix)
| Step | Result | Evidence |
|---|---|---|
| Installed v1.0.3 from the GitHub release (`versionName=1.0.3`); Settings → Advanced → "Remove orphaned import data" → confirmation dialog → snackbar "Removed N activities, 0 kept" | PASS | (screenshot kept private) |
| Activities after the cleanup: no ×100-distance rows left; the merged sessions still carry both Health Connect and CSV chips with correct distances; the full CSV import intact | PASS | (screenshot kept private) |
- BUG-12 → VERIFIED on the phone. Hotfix commit cherry-picked into main (0a12cfc) so 1.1.0 carries it.

## Session 10 — 2026-09-13 (emulator myhealth_api35, P12 cycling, debug build of 1.1.0)
Seeder extended (`tools/hc-seeder`, `SeederRides.kt`): every Tuesday a 60-min `BIKING_STATIONARY` ride with 5-s `PowerRecord` + `CyclingPedalingCadenceRecord` samples (220 W ±3, exactly 300 W from minute 20 to 40) and **no** heart rate; every Saturday a 40.2 km `BIKING` ride in 1 h 15 min with HR, distance and speed. 45 days → Exercise=51, Power=6, PedalCadence=6.

| Step | Result | Evidence |
|---|---|---|
| Upgrade path 1: 1.0.3 debug build synced the seed (rides listed as `Trainer · 1h 00m · TRIMP 90` = RPE estimate, `Rennrad · 40.20 km · TRIMP 105`) | PASS | emu_p12_01_activities_103.png |
| Upgrade path 2: 1.1.0 debug installed over it (`adb install -r -d`, DB 4→5 on populated data), launched, "Sync now" without the new Power permission → no error, all rides still there, trainer detail still "90.0 (RPE estimate)", no power card | PASS | emu_p12_02_trainer_detail_nopower.png |
| Integrations lists "Power" as the only not-granted row, "Workouts and cycling cadence" relabelled; "Grant permissions" sheet asks for Power only | PASS | emu_p12_03_integrations.png |
| After granting Power, "Sync now" did **not** bring power in (changes token reports nothing for unchanged sessions) — a backfill did (trainer row → TRIMP 121). Fix committed: a newly granted `OPTIONAL_DETAIL` permission enqueues `HealthSyncWorker` with `KEY_REREAD_DAYS = 90` → `HcSyncService.rereadExerciseDetail` re-ingests the last 90 days of sessions (BUG-13, see below) | PASS after fix | — |
| Trainer ride detail: TRIMP "121.0 (from power (TSS))", cadence "88 rpm", power card avg 247 W / NP 256 W / max 300 W / IF 0.90 / TSS 81, "Power over time" chart with the 300 W block, HR and speed cards report no stream | PASS | emu_p12_07_trainer_detail_power.png, emu_p12_08_trainer_detail_scrolled.png, emu_p12_09_trainer_detail_chart.png |
| Bike & power screen: FTP 285 W "20-minute best from a ride on 4 Aug", power bests 5 min 300 W / 20 min 300 W / 60 min 247 W, time best 40 km 1:14:38 (est.) = 4478 s, hint about the indoor trainer | PASS | emu_p12_06_bike_screen.png |
| Settings: Cycling section with FTP override (hint "Estimated 285 W from a 20-minute best on 4 Aug."), "Indoor trainer available" switch (turned on), "Rides / week" cap set to 2 (label renamed to "Ride sessions / week cap" afterwards, POLISH-15) | PASS | emu_p12_10_settings_cycling.png, emu_p12_11_settings_rides.png, emu_p12_13_settings_trainer_on.png |
| Goals: New goal → type list has Bike FTP / Bike volume / Bike event; Bike FTP 300 W created; list shows "285 W (from a 20-minute best) · target 300 W — on track." | PASS | emu_p12_14_goal_bike_ftp_form.png, emu_p12_15_goals_list.png |
| Training → Generate suggestions: Base phase, two "Trainer session" rides (cap 2 → "Cycling: 2 of 2 sessions this week"), rationale "the trainer keeps the intensity controlled indoors for FTP 300" and "Towards your FTP target: threshold and steady riding are what raise it", mobility on the other days; Accept selected → next week shows "Trainer session · 59 min · 106 AU · Planned" | PASS | emu_p12_16_suggestions.png … emu_p12_24_next_week_planned.png |

- BUG-13 (found in this session, fixed before release): after granting a *new* per-session Health Connect permission (`READ_POWER`), neither the incremental sync (changes token: unchanged sessions are not reported) nor a backfill (never revisits windows below its watermark) re-reads the already-synced rides, so their power never arrived. Fix: `IntegrationsViewModel.onPermissionsResult` detects a newly granted `HcIntegration.optionalDetailPermissions` entry and calls `SyncScheduler.rereadExerciseDetail(90)`; `HealthSyncWorker` runs `HcSyncService.rereadExerciseDetail(days)` (plain window read + merge). Tests `IntegrationsUiStateTest.a_newly_granted_power_permission_is_reported_as_new_detail`, `no_re_read_when_the_granted_set_was_not_loaded_yet_or_nothing_detail_changed`.
- POLISH-15: the cap field was labelled "Rides / week" next to "Run sessions / week cap" → "Ride sessions / week cap" (Settings) / "Ride sessions / week" (onboarding); goal headline "300.0 W FTP" → "300 W FTP" (whole watts, also for the consistency goal's sessions/week).
- NOTE-14: on the emulator the Bike screen dates a tied best (all six trainer rides hold 300 W for 20 min) by its first occurrence (4 Aug); acceptable.
- NOTE-15: "Weekly load target 773 AU; 667 AU still unallocated." in a session's rationale is the running allocation *at that candidate*, not the week's final state — pre-existing wording, unchanged.

- **INCIDENT-1 (2026-09-13 17:34):** the P12.5 instrumented run (`./gradlew :app:connectedDebugAndroidTest`, delegated to an agent without `ANDROID_SERIAL`) targeted every attached device. On the owner's Pixel the install failed (`INSTALL_FAILED_VERSION_DOWNGRADE`, debug 1.0.2 build vs installed 1.0.3) but the task's uninstall step still removed `com.myhealth` — logcat "ACTION_PACKAGE_FULLY_REMOVED pkg=com.myhealth" — and with it all app data (profile, the full activity CSV import, settings, goals; no JSON backup existed). Recovery: 1.1.0 installed from the release, onboarding to be redone by the owner, Health Connect grant + backfill + CSV re-import from `/sdcard/Download/Garmin_Activities_full.csv` afterwards. Guard: `tools/connected.sh` (rule R13) is now the only way to run instrumented tests.

## Session 11 — 2026-09-13 (Pixel 7a, v1.1.0 from the GitHub release, after INCIDENT-1)
Owner redid onboarding, re-imported the full CSV and ran the Health Connect backfill; then:

| Step | Result | Evidence |
|---|---|---|
| Activities → Cycle filter lists the re-imported rides | PASS | (screenshot kept private) |
| Settings → Cycling: "Indoor trainer available" switched on; "Ride sessions / week cap" set next to the run/strength/soccer caps; FTP override field present, no estimate hint | PASS | (screenshot kept private) |
| Bike & power: "No FTP estimate yet — ride with a power meter, or set the override in Settings." and "No ride bests yet" — correct: the owner's last ride with power was outside the 90-day window; the outdoor rides carry HR only and none is within 1 % of a canonical distance | PASS (expected) | (screenshot kept private) |
| Integrations: "Power" row listed (not granted — Garmin Connect writes no power to Health Connect) | PASS | (screenshot kept private) |
| Training → Generate suggestions: Base phase, a very low target, week = long runs + mobility, **no ride** — the target is far too low because most of the history has no TRIMP (below) | FAIL → BUG-14 | (screenshot kept private) |
| Load & Recovery: low CTL and ACWR, "Not enough training history yet"; older activities show no TRIMP at all | FAIL → BUG-14 | (screenshot kept private) |
| Backup → Export backup → picker (Downloads, `myhealth-backup-<date>.json`) → "Export finished · … rows over … tables written"; copied to the workstation (`~/my_health_backups/`) | PASS | (screenshot kept private) |

- BUG-14 (load recompute cancelled): `SyncScheduler.requestLoadRecompute` enqueued with `ExistingWorkPolicy.REPLACE`, which cancels a run that is already executing. The full CSV import requested a recompute from the start of the history; the Health Connect backfill that followed requested one from its own (recent) `fromDay` and cancelled the historical run, so every activity before the backfill window kept `trimp = null` and the weekly target collapsed to a starter level. Fix (1.1.1): `APPEND_OR_REPLACE` (the new request chains behind the running one), plus Settings → Advanced → "Recompute training load" (`requestLoadRecompute(0)`, clamped to the first activity) as the manual repair.
| 1.1.1 installed over 1.1.0 (data kept); Settings → Advanced → "Recompute training load" → snackbar "Training load recompute scheduled…"; ~3 min later the older activities carry TRIMP and Load & Recovery shows a higher CTL | PASS | (screenshot kept private) |
| Load note "Not enough training history yet for a reliable ACWR" persists — legitimate: fewer than `MIN_DAYS_WITH_DATA` of the last 28 days carry a session (the owner's real training frequency), not a data gap | PASS (expected) | — |
| Training → Generate suggestions after the recompute: "Base · target … · … suggested" (target = CTL × 7), week = long run, strength full, mobility — still **no ride**: with the ride cap at 2 rides are *eligible*, but in the BASE phase the run/strength rows outscore them and the budget is spent before a ride is placed; a `BIKE_*` goal switches to the cycling phase table (verified on the emulator, session 10). Owner can add a bike goal (Goals → New goal → Bike FTP / Bike volume / Bike event) to get rides planned | PASS (by design) | (screenshot kept private) |
- NOTE-16: consider a "rides per week" *floor* (not only a cap) or letting a non-zero ride cap count as a soft preference, so a rider without a bike goal still sees a ride in a run-dominated base week.

## Session 12 — 2026-09-13 (emulator, 0.3.0 debug build: active recovery on rest days)
Fresh profile (onboarding: run 2 / strength 1 / ride 2 sessions per week, mobility on rest days on), no sync → starter week.

| Step | Result | Evidence |
|---|---|---|
| Training → Generate suggestions: "Base · target 150 AU · 304 AU suggested", 11 items: Sun long run, Mon strength full, Tue **recovery spin** + mobility ("Active recovery: an easy 30-minute spin keeps the legs moving without adding load."), Wed **recovery run** + mobility, the remaining rest days alternate the same way with one mobility-only true rest day | PASS | emu_p13_03_active_recovery.png, emu_p13_04_active_recovery_2.png |
- The fillers show the mobility-style nominal score and 30 min; the header's "304 AU suggested" includes them (they are real minutes on the calendar), while the budget lines ("150 AU still unallocated") only count the greedy loop's sessions — by design (§3.5.6 step 7c).
- NOTE-17: with both sports available the first filler's sport is decided by the epoch-day parity (deterministic, but arbitrary); a "preferred recovery sport" setting could replace it.
- 0.3.0 was published (GitHub release v0.3.0, private archive repository) but **not yet installed on the Pixel** — the phone was unplugged when the build finished.
| 0.3.0 installed on the Pixel over 0.2.1 (data kept); owner generated a week and confirmed the active-recovery fillers ("Works on the phone") | PASS (owner) | — |

## Session 13 — 2026-09-13 (emulator, 0.4.0 debug build: zones, paces, intervals, strength)
Upgrade path: the emulator carried the 0.3.0 build with a fresh profile, an accepted week and the 45-day Health Connect seed; the 0.4.0 debug build was installed over it (`install -r`, DB 5 → 6 in place).

| Step | Result | Evidence |
|---|---|---|
| Launch after the in-place upgrade: no crash, Today shows the previously planned long run; Health Connect permissions granted and "Sync now" delivers 6 activities and daily data (recovery 98/100) | PASS | — |
| More → **Zones & paces**: Z1 · Recovery · 54–132 bpm … Z5 open-ended; "Heart-rate reserve (Karvonen)"; pace per zone with "Measured from 13 runs" / "Measured from 4 runs" / "Modelled from your VDOT"; Daniels paces; polarisation "Easy 39 % · Moderate 28 % · Hard 33 %" with the < 70 % hint; session-type table with n/a for strength | PASS after fix | emu_p14_05_zones.png, emu_p14_06_zones_bottom.png |
| More → **Strength workouts**: the six seeded templates with kind chip, exercise count and minutes ("Upper A · 6 exercises · about 44 min") | PASS | emu_p14_07_workouts.png |
| More → **Exercises**: list with equipment chips and primary muscles; "Barbell bench press" detail: body figure, equipment, movement pattern, cue, "Add to workout…" | PASS | emu_p14_08_exercises.png, emu_p14_09_exercise_detail.png |
| **Load & Recovery** → Muscle load card: heat-map figure, legend, "Legs are loaded — an upper-body day fits today" | PASS | emu_p14_10_load_muscle.png |
| Settings → **Heart-rate zones**: live preview "Z1 · Recovery · 60–133 bpm … Z5 · VO2max · 171+ bpm" | PASS | emu_p14_11_settings_zones.png |
| Training → Generate: sessions carry zone chips ("Z2 · 134–145 bpm"), "Target pace 5:27/km, zone 2."; bike intervals "4 × 8:00 @ 271–299 W with 4:00 recovery — from an FTP of 285 W."; "Strength full" with "Workout: Full body A — 6 exercises, about 44 min." and "Legs are fresh and nothing hard is due in the next 48 h — a leg day fits here." | PASS | — |

- POLISH-16 (fixed): the Zones screen showed its empty state whenever there were no runs and no VDOT, hiding the zone table that follows from the profile alone → `hasAnyData` now counts a zone model.
- POLISH-17 (fixed): the polarisation hint printed "70%%" (an escaped percent in a string used without format arguments) → "70 %".
- POLISH-18 → FIXED in 0.4.1 (P15.1): jointed body model with rounded silhouette and organic muscle regions (emu_p15_01…03).

## Session 14 — 2026-09-14 (emulator myhealth_api35, P15.1 body figure v2, debug build of 0.4.0)

Fresh AVD state: onboarding completed by hand (test profile "Alex"; sex, birth date, height and weight entered by hand), then
four activities imported from a hand-written Garmin-style CSV (`/sdcard/Download/acts.csv`: a 14 km
long run 13 Sep, a 90-min soccer session 11 Sep, a 45-min gym session 12 Sep, an 8 km easy run
9 Sep) — Health Connect is not granted on this AVD, and the CSV import is the quickest way to give
the muscle-load engine a fortnight of load.

| Step | Result | Evidence |
|---|---|---|
| More → Exercises → **Barbell bench press**: both silhouettes read as a human body — rounded head, neck, sloping shoulders, tapered torso, arms held clear of the hips, hands, thighs, calves, feet — with the chest filled `primary` and the front deltoids and triceps at 35 % | PASS | emu_p15_01_figure_bench.png |
| More → Exercises → search "squat" → **Barbell back squat**: quadriceps and glutes `primary`, hamstrings, lower back and the six abdominal blocks at 35 % | PASS | emu_p15_02_figure_squat.png |
| **Load & Recovery** → Muscle load: heat map with quads/hamstrings/calves/glutes fatigued and chest/lats/traps/lower back loaded; "Quads — 42 AU · Fatigued", "Calves — 39 AU · Fatigued", "Hamstrings — 38 AU · Fatigued" | PASS | emu_p15_03_heatmap.png |
| Exercises screen muscle filter: tapping a pectoral on the front figure filters the list to chest exercises, tapping it again clears the filter (ray-cast hit test, P15.1) | PASS | — |

- POLISH-18 (fixed): the flat-rectangle figure is replaced by the P15.1 jointed model — rounded,
  spline-drawn parts unioned into one silhouette, with organic muscle regions inside each part.
- POLISH-19 (found, **not** fixed — outside P15.1's file list): `ui/strength/SetLogSheet.kt` puts its
  `LazyColumn` in the sheet's `Column` with no `weight(1f)`, so the list takes the whole sheet and the
  "Skip logging" / "Save" row is laid out below the bottom edge. Training → a strength session →
  "Mark done" therefore cannot be completed from the sheet: the session stays `Planned` whether the
  sheet is dismissed or swiped. One-line fix (`Modifier.weight(1f)` on the `LazyColumn`).
- Note: the Load screen calls `BodyFigure(modifier = Modifier.fillMaxWidth())` with no height. Before
  P15.1 that let the front figure take the whole row and pushed the back one off the edge; `BodyFigure`
  now gives each silhouette half the width itself, so every call site is safe without changing them.

## Session 14 — 2026-09-14 (emulator, 0.5.0 debug build: my equipment + progression)
| Step | Result | Evidence |
|---|---|---|
| Strength workouts → Upper A editor: rows print the prescription ("Barbell bench press · 3 × 10 @ 30 kg" — 0.40 × the 75 kg fallback weight, the template's 10 reps) | PASS | emu_p16_01_upper_a.png |
| Settings → Strength → My equipment: all chips selected by default; deselecting Barbell, Machine, Cable, Kettlebell, Band, Medicine ball → Exercises shows "Only my equipment" on and only Bodyweight/Dumbbell rows | PASS | emu_p16_02_settings_equipment.png, emu_p16_03_equipment_set.png, emu_p16_04_exercises_filtered.png |
| Strength workouts → Upper A → More actions → "Plan for a day…" → today → Training shows "Strength upper · Workout: Upper A"; More actions → Mark done → set-log sheet with per-exercise Too easy / Easy / Hard / Too hard | PASS | emu_p16_05_training_card.png, emu_p16_06_setlog_sheet.png |
| Bench press feedback "Too easy" → Save → session Completed; Exercises → Barbell bench press → Progression card: "7 reps @ 30 kg", "Last: Too easy on 14 Sep", history "14 Sep · 3 × 5 @ 30 kg" | PASS | emu_p16_07_after_save.png, emu_p16_08_progression_card.png |
- POLISH-20 (fixed): `NumberField` initialised its text once, so the set-log sheet's fields kept showing the template's "10" / empty load after the prescriptions (5 reps @ 30 kg) arrived — the saved rows were right, the fields were stale. The field now resyncs when its external value changes to something its text does not already parse to.
- After POLISH-20 (Full body A session): the sheet opens pre-filled ("Goblet squat · set 1 · 8 reps · 15.0 kg"); "Easy" for the goblet squat → Save → snackbar "Next time: Goblet squat 3 × 9 @ 15 kg; Push-up 3 × 8; Romanian deadlift 3 × 5 @ 35 kg; Inverted row 3 × 8; Overhead press 3 × 5 @ 17.5 kg; Plank 3 × 30 s" (emu_p16_09_sheet_prefilled.png, emu_p16_10_next_time.png). PASS.
- NOTE-18: the planned-session card's workout line prints the template rows ("Barbell bench press 3 × 10") rather than the current prescription; the editor, the sheet and the snackbar do. Small follow-up.

## Session 15 — 2026-09-14 (Pixel 7a, 0.5.0 installed over 0.3.0)
JSON backup exported first (`myhealth-backup-<date>.json`, copied to `~/my_health_backups/`), then `install -r`: DB 5 → 7 in place, Today shows targets and load, no errors.

| Step | Result | Evidence |
|---|---|---|
| Zones & paces with the owner's data: zones computed from the owner's resting HR (from Garmin) and observed max HR; pace bands from the CSV-only history "partly modelled" for Z2–Z4, Z5 "not enough data yet"; Daniels paces "not enough data yet" (no run best inside the VDOT window); 28-day intensity split shown | PASS | (screenshot kept private) |
| Strength workouts → Upper A: "Barbell bench press · 3 × 10 @ …" (load = 0.40 × body weight rounded down) | PASS | (screenshot kept private) |
| Load & Recovery → Muscle load: heat map with the legs loaded, "Legs are loaded — an upper-body day fits today" | PASS | (screenshot kept private) |
| Training → Generate: "Base · target … · … suggested · … rest days · N unlocked planned sessions will be replaced" and **only fillers** (recovery run/spin + mobility) — the owner's hand-planned soccer sessions sit in the horizon; the low target (after two quiet weeks) leaves almost no budget once the fixed load is counted, and the proposal would have **deleted the hand-planned sessions on accept** | FAIL → BUG-15 | (screenshot kept private) |

- BUG-15 (fixed in 0.5.1): `PlanDao.getReplaceableSessions` treated every unlocked PLANNED session as replaceable, including sessions the user planned by hand; and the engine only seeded *locked* sessions as fixed, so a hand-planned match was neither protected nor counted. Fix: replaceable = unlocked, PLANNED **and** `sourceSuggestionId IS NOT NULL`; the suggester seeds locked **or** hand-planned sessions as fixed items. Header wording: "earlier suggested sessions will be replaced". Test `accept_replaces_unlocked_planned_sessions_in_the_horizon_and_keeps_locked_and_completed_ones` extended with a manual session.
- NOTE-19 → FIXED (0.5.2): the owner's backup showed the weekly soccer sessions are **locked** hand-planned sessions whose fixed load already exceeds the weekly target, so the engine correctly adds only recovery. The review header now appends "your locked and hand-planned sessions already carry X AU of the Y AU target, so only recovery is suggested" (`SuggestionReviewUiState.fixedLoad`).
- NOTE-20 → FIXED (0.5.2): `lightweightHrZoneModel` takes the last days' resting-HR readings; Today passes the day's summary, the suggestion review reads the 7-day window — the chips now print the same bpm ranges as Zones & paces (the planned-session editor still uses the profile-only model).
| 0.5.1 → 0.5.2 installed over the data; Regenerate: header "Base · target … · … suggested · … rest days · N earlier suggested sessions will be replaced · your locked and hand-planned sessions already carry X AU of the Y AU target, so only recovery is suggested"; Z1 chip computed from the measured resting HR | PASS | (screenshot kept private) |
- NOTE-21 → FIXED (0.5.3): one `resolveHrZoneModel` (resting HR of the last 7 days + highest observed HR of the last 365 days) now serves Zones & paces, Today, the suggestion review and the planned-session editor; the Zones screen previously read the observed max from its 90-day pace window only.

## Session 16 — 2026-09-14 (emulator, 0.6.0 debug build: mobility)
| Step | Result | Evidence |
|---|---|---|
| Exercises → kind chips All / Strength / Mobility; Mobility shows rows like "Mobility · Glutes, Adductors"; Pigeon stretch detail: glutes primary + adductors secondary on the figure, "One side at a time", "Timed hold", Progression "~30 s" | PASS | emu_p17_01_exercises_mobility.png, emu_p17_02_pigeon.png |
| Strength workouts: "Mobility · lower / upper / full" kinds, "8 exercises · about 21 min", rows "Cat-cow 2 × 45 s, Thread the needle 2 × 45 s…" | PASS | emu_p17_03_workouts.png, emu_p17_04_mobility_lower.png |
| Training → Generate: the rest-day mobility session carries "Mobility: a full-body routine keeps everything moving." (muscle load present, legs not loaded → full); Accept → card "Routine: Mobility full A"; Mark done → "Mobility full A — log your sets" with Seconds pre-filled (30) and the feedback buttons | PASS | emu_p17_05_week_routine.png, emu_p17_06_mobility_setlog.png |

## Session 17 — 2026-09-14 (emulator, 0.7.0 debug build: exercise animations)
| Step | Result | Evidence |
|---|---|---|
| Exercise detail → "Animation" section above the muscle map: Goblet squat animates in profile (goblet hold, hips/knees flex and extend, quads highlighted on the moving segments) | PASS | emu_p18_squat_a.png, emu_p18_squat_b.png |
| Pigeon stretch: profile kneeling hip stretch with a held end pose, glutes highlighted | PASS | emu_p18_pigeon_a.png, emu_p18_pigeon_b.png |
| Push-up: horizontal body with the arm extending and bending — first render was tiny (a 213-unit body inside the 100-wide portrait box) → POLISH-21 fixed: the renderer takes a `BodyViewport`, `clipViewport` fits each clip to the union of its silhouettes, horizontal clips get a landscape box | PASS after fix | emu_p18_pushup_a.png (before), emu_p18_pushup2_a.png / _b.png (after) |
| Lateral raise: front view, arms sweep out to horizontal, deltoids highlighted, fills the frame | PASS | emu_p18_lateral_a.png, emu_p18_lateral_b.png |
- POLISH-21 (fixed in 0.7.0): fit-to-content viewport for animated and static figures; tests `anui04_viewport_contains_every_keyframe_silhouette`, `anui05_horizontal_clip_viewport_is_landscape`.
- NOTE-22: the pigeon pose reads as a lunge more than a floor stretch (the figure floats; no ground line). A ground line under clips with a floor contact and a slightly lower kneeling pose would help.

## Session 18 — 2026-09-14 (JVM render loop + emulator, 0.7.1 debug build: animations re-authored)
The owner reviewed the 0.7.0 animations on the review sheet and judged "most of them are pretty much off". Every one of the 44 clips was inspected on contact sheets (keyframes + in-between frames, floor line, joint dots) rendered from the app's own skeleton geometry through a JavaScript mirror of `BodyModel.kt` (fidelity check against the Kotlin export: worst deviation 0.013 units, i.e. rounding).

| Finding in 0.7.0 | Fix in 0.7.1 |
|---|---|
| Six different hip stretches (couch, pigeon, 90/90, figure-four, quad stretch, world's greatest) shared one kneeling-lunge clip; leg swings played the hamstring stretch; bird dog, cat-cow, child's pose, downward dog and thread-the-needle all played one quadruped rock; all six foam-roll drills played one prone pose; four shoulder/arm stretches shared the triceps stretch | one clip per distinct movement: 76 clips for 87 exercises (only bench/incline, pull-up/chin-up, glute bridge/hip thrust, three seated foam rolls and the two curls still share) |
| Leg curl / leg extension / Nordic played a standing knee bend that sank the whole figure by 25 units | `LEG_CURL` prone, `LEG_EXTENSION` seated, `NORDIC` kneeling with the trunk pivoting at the knee |
| Back squat swung the arms to horizontal; front squat folded the forearms over the face | hands on the bar (`shoulder 35 / elbow −150`), elbows high with the hands at the shoulders; goblet, deep-squat hold, wall sit and leg press got their own clips |
| Back extension lifted the legs with the trunk (`hip` not counter-rotated); prone figures had their toes below the floor | `hip = −2 → 22` keeps the legs down; prone clips point the toes; every lying clip is grounded on its whole outline |
| Hip thrust rotated the trunk about the hips with floating feet; jump never left the floor; step-up had no step; dip and inverted row moved the hands instead of the body | bridge pivots at the shoulders (`torso −26`, head kept level) with the feet planted; jump has an airborne keyframe 28 units up; step-up lifts the body onto a 24-unit step; hands are pinned to the bar and the body moves |
| Side plank lay flat with the arms sticking out; open book lay with the arms along the body | side plank is propped on the forearm with the top arm to the ceiling (`solve` on elbows + feet); open book sweeps the top arm from the ceiling over the head to the floor behind |
| Shoulder CARs was an up-and-down arc | a true circle in profile: three keyframes 120° apart, `CIRCLE` timing, shortest-arc interpolation |

| Step | Result | Evidence |
|---|---|---|
| Contact sheets of all 76 clips (4 keyframes + 5 in-betweens each) reviewed twice by the lead; large renders of the 12 doubtful ones (side plank, open book, hip thrust, world's greatest, couch stretch, standing quad stretch, rock-back, child's pose, leg curl, hollow, twist, thread the needle) | PASS | scratchpad `anim/new_*.png`, `anim/big_*.png` |
| Kotlin export (`TmpExportTest`, deleted before commit) matches the JavaScript authoring model frame for frame | PASS | worst deviation 0.013 |
| `bash tools/verify.sh` — `an01`…`an09`, `anui01`…`anui06` updated/added; `an07` accepts the real forearm-plank slope | see STATUS | |
| Review sheet regenerated from the Kotlin export and republished (76 clips, floor line under every figure) | PASS | artifact link in the delivery message |

- NOTE-22 (pigeon floats, no ground line): closed by 0.7.1 — every clip is planted on a floor line in the source, and the review sheet draws the floor.
- NOTE-23: the front-view 90/90 hip switch and the Russian twist are the two clips this camera cannot show faithfully (both need a top-down or three-quarter view); they read as "knees swinging side to side" and "arms sweeping side to side" respectively.
- NOTE-24: `an02`'s constant-scale rule means a clip with a very tall and a very wide keyframe (jump, world's greatest) is scaled to fit its widest frame; the viewport fit (POLISH-21) hides this.


## Session 19 — 2026-10-04 (emulator, 0.8.0 debug build: goal-driven training, workout pool)
Emulator `myhealth_api35`, today 2026-10-04. Fresh app data; Health Connect re-seeded with `tools/emu.sh seed 45` (now clears first); onboarding: soccer 1, runs 3, strength 2.

| Step | Result | Evidence |
|---|---|---|
| Goal "5k" 5 km in <goal time>, date <goal date>, **Race on this date off** → list shows "5 km in <goal time> **by** <goal date>"; before any sync "No 5 km effort in the last 6 months — run a time trial…", neutral colour, no bar | PASS | (screenshot kept private) |
| Goal "City Half" HM in <goal time>, race <race date>, primary → "half marathon in <goal time> **on** <race date>"; switch text "The plan builds towards this day and tapers before it." | PASS | (screenshot kept private) |
| After HC sync: 5k "Recent best 20:55 (26 Sep) — behind"; HM "Predicted 1:36:13 from your 5 km on 26 Sep — behind" (recent form, not the all-time table) | PASS | (screenshot kept private) |
| Training header: "Base · Build from <date> · City Half on <race date>" | PASS | |
| Generate 5–11 Oct (non-starter week): strength days name **Upper A** then **Upper B** (pool rotation); long run "13 km — building from 12 km (your longest in 4 weeks) towards 19.0 km for City Half on <race date>" at 5:27/km; no time trial (5 km effort 8 days old); no goal-pace session (Base phase) | PASS | (screenshot kept private) |
| Accept → `planned_session.workoutId` = 1 (Upper A, Tue) and 2 (Upper B, Fri); Training card shows "Workout: Upper A" and its exercises | PASS | DB pull |
| Strength workouts: uncheck Upper A + Upper B ("Use in suggestions") → generate 19–25 Oct: no upper day, one strength day with **Lower A** | PASS | (screenshot kept private) |
| Onboarding step 3: clear "Run sessions / week", type 3 | **FAIL → fixed**: field showed 14 ("0" re-inserted, "30" clamped); fixed in `NumberField`, re-tested: 3 | (screenshot kept private) |
| `bash tools/verify.sh` | PASS | 1044 unit tests, lint clean |
| `bash tools/connected.sh emulator-5554` | PASS | 25/25 (new `NumberFieldTest`, `workouts_useInSuggestions_checkbox_persists`, `migration_7_to_8_…`) |

- BUG-16 (fixed in 0.8.0): `NumberField` re-filled a field the user had just emptied when the caller maps empty to a value; see PLAN P19 "As built".
- BUG-17 (fixed in 0.8.0): Strength workouts opened scrolled to the bottom on first launch (list rendered while seeding).
- NOTE-25: the first emulator pass showed a 21.2 km run in 59 min (VDOT 83.6, "HM 58:41"). Cause: the seeder ran twice without clearing, so Health Connect held two distance records per session window; the app summed them correctly. `tools/emu.sh seed` now passes `clear=true`. Not an app bug, but a duplicate-writing source on a real phone would produce the same effect.
- NOTE-26: the Settings instrumented tests had been failing since P16.2 on an ambiguous `hasScrollAction()` node (equipment chips); fixed with `verticalScroller()`.


## Session 20 — 2026-10-04 (emulator, 0.8.1 debug build: side goal)
Emulator `myhealth_api35`; onboarding, then the owner's phone backup imported with **Replace**.

| Step | Result | Evidence |
|---|---|---|
| Goals as on the phone (5k best primary, race <goal date>): generate 4–10 Oct → recovery week, no side session (phase off) | PASS | |
| City Half → Make primary; 5k best → Race on this date **off** → "5 km in <goal time> by <goal date>"; header "Base · Build from <date> · City Half on <race date>" | PASS | |
| 4–10 Oct is a recovery week → still no side session (by design) | PASS | |
| Emulator clock moved to 11 Oct (non-recovery week) → generate: **Interval run "4 × 1000 m @ …"** (rep pace from current form) with "5k best: 5 km in <goal time> by <goal date> — one 5 km-specific interval session a week until then." and "Goal pace <goal pace> (5 km in <goal time>). Current form predicts … (+… %) — reps stay at your current paces until you are within 3 %."; only one interval run in the week | PASS | (screenshot kept private) |
| Clock restored (auto time) | PASS | |
| `bash tools/verify.sh` | PASS | 1052 unit tests (+8 `SideGoalTest`), lint clean |
| `bash tools/connected.sh emulator-5554` | PASS | 25/25 |

- NOTE-27: the "N AU still unallocated" line of a pre-placed session (benchmark or side goal) shows the budget *before* that session, as for every other session; with a low weekly target the side session alone can exceed it (the 11-Oct check had a low target because the jumped clock left a week without training).


## Session 21 — 2026-10-04 (emulator, 0.9.0 debug build: P20.1 shared module)
Emulator `myhealth_api35`; onboarding, then the owner's backups imported with **Replace**. No user-visible change is expected.

| Step | Result | Evidence |
|---|---|---|
| `bash tools/verify.sh` | PASS | 1057 unit tests (+4 `NumberFormatTest`, +1 architecture rule), lint clean, release 14.7 MB |
| `bash tools/connected.sh emulator-5554` | PASS | 25/25 |
| Import the owner's latest backup (schema 8) → export → JSON diff | PASS | only `exportedAtMillis` and `appVersion` differ |
| Import the owner's older backup (schema 7) → export → diff | PASS | only `goal.isRace` / `strengthWorkout.useInSuggestions` defaults added (DB v8 columns) |
| Goals: recent-best and predicted-time status texts | PASS | same as 0.8.1 |
| Training → Generate (4–10 Oct): "Recovery week" header with target and suggested load, recovery runs at the owner's easy pace, mobility/spin rationale texts | PASS | identical to the 0.8.1 walkthrough (session 20) |
| Nutrition (daily target) and Calendar month view render | PASS | (screenshot kept private) |

- NOTE-28: the nutrition target was first not compared with the phone (it had disconnected). Done after the phone came back: phone backup taken (pre-0.9.0), v0.9.0 installed from the release; the Pixel and the same backup on the emulator show the same nutrition target with the v0.8.1 release and again after upgrading in place to v0.9.0. Goals and the Training phase line on the Pixel match 0.8.1.


## Session 22 — 2026-10-04 (emulator, 0.9.1 debug build: P19.6 + BUG-18)
Emulator `myhealth_api35`, date 4 Oct 2026; onboarding, then the owner's pre-0.9.0 backup imported with **Replace**.

| Step | Result | Evidence |
|---|---|---|
| `bash tools/verify.sh` | PASS | 1068 unit tests, lint clean, release 14.8 MB |
| `bash tools/connected.sh emulator-5554` | PASS | 25/25 (phone attached, untouched) |
| After the import, `daily_load` still holds the stale 0.9.0 values (including a run of zero CTL days) | FOUND | import did not recompute → fixed: recompute after import and on app start |
| Restart the app (recompute on start) → `daily_load` via `sqlite3` | PASS | CTL over the last weeks up to today — identical to an independent EWMA over the backup's TRIMP; an older consistent row kept its old `computedAtMillis` |
| Load screen | PASS | ATL / CTL / ACWR from the recompute, continuous curve (screenshot kept private) |
| Training → Generate | PASS | "Base" week (was "Recovery week"); target = the 25 % ramp cap over last week's load (below the uncapped CTL × 7 × 1.20); long run building towards the half-marathon distance (screenshot kept private) |

- NOTE-29: the down-week paths (all four reasons, the `DOWN_WEEK` line on every budgeted session, taper protection) are covered by `dw01`…`dw09` on the engine; the owner's real history triggers none of them this week, so the emulator shows only the "no down week" path.
- NOTE-30: the Load screen's "Not enough training history yet for a reliable ACWR" stays: the rule asks for activity on ≥ 21 of the last 28 days, and the owner trains on fewer days. Pre-existing, unchanged.


## Session 23 — 2026-10-04 (emulator, 0.9.2 **release** build: P20.2 data layer in the shared module)
Emulator `myhealth_api35`, date 4 Oct 2026. Release build (R8) because P20.2 replaced libraries (OkHttp → Ktor, Garmin FIT SDK → own decoder, `ZipInputStream` → own reader). Started from the published **v0.9.1** APK: onboarding, the owner's pre-0.9.1 backup imported with **Replace**, then upgraded in place.

| Step | Result | Evidence |
|---|---|---|
| `bash tools/verify.sh` | PASS | 1091 unit tests (2 corpus tests skipped without the env vars; run locally over the owner's Garmin export: tens of thousands of FIT files / 0 differences, both ZIP exports identical), lint clean, release 14.0 MB |
| `bash tools/connected.sh emulator-5554` | PASS | 26/26 (+ `bug19_oversized_import_errors_are_repaired_without_reading_them`); phone attached, untouched |
| v0.9.1: Today, Goals, Load → upgrade to 0.9.2 → same screens | PASS | Today's nutrition target, goal status texts and load values — identical |
| Export on 0.9.1 and on 0.9.2 → JSON diff | PASS | only `appVersion`, `exportedAtMillis` and the start-up recompute's `dailyLoad.computedAtMillis` differ |
| 0.9.2: import the 0.9.1 export (Replace) → export → diff | PASS | same three fields only |
| FIT import `ride_power.fit`, `run_5k.fit`; re-pick the ride | PASS | ride: power-only, avg 246 W / NP 255 W, power stream (screenshot kept private); run: 5.00 km, 2 laps, TRIMP from HR samples, zones (screenshot kept private); second pick → "Already imported" |
| CSV import `garmin_de.csv` | PASS | 3 parsed / 3 saved; "Feierabendrunde, flach" 11 May 17:30, 30.25 km; "Morgenlauf" 10 May 09:00, 10.52 km |
| Garmin export ZIP (owner's, over a hundred MB, tens of thousands of entries) | **FOUND** | BUG-19: Import screen crashed with `SQLiteBlobTooBigException` — reproduced identically with the published v0.9.1 (hundreds parsed, tens of thousands of "no session" errors, a several-MB `errorsJson`) |
| BUG-19 fix: v0.9.1 import crash → app stuck → upgrade to fixed 0.9.2 | PASS | Import screen opens again, the row shows "1 errors" (repaired); "Import anyway" on 0.9.2: N parsed · 0 saved · N duplicates · 0 errors in 36 s — the okio SHA-256 and the new decoder's activity ids match what 0.9.1 stored |
| Open Food Facts lookup (Ktor, R8) | PASS | 3017620422003 → Nutella, 539 kcal / 6.3 P / 57.5 C / 30.9 F per 100 g (screenshot kept private), saved; unknown code → "Product not found in Open Food Facts." + Retry |
| Settings persist (DataStore) | PASS | sync interval 6 → 12 h and "Track menstrual cycle" on survive a force-stop (Cycle entry appears in More) |
| Health Connect: grant via the sheet, seeder data, Sync now | PASS | all four streams synced 19:49; seeded "Rennrad" 3 Oct: 40.20 km, HR samples, speed stream, zones (screenshot kept private); resting HR and 14 nights of sleep on Body & Health (screenshot kept private) |

- NOTE-31: `ride_power.fit` (16:25 UTC) lands next to the owner's CSV virtual ride (16:25 local time) instead of merging with it: the fixture's instant was taken from the owner's ride as UTC. Fixture artefact; matcher and mapper unchanged, decoder output identical to the SDK.


## Session 24 — 2026-10-04 (emulator, 0.9.3 **release** build: P20.3 UI in the shared module on Compose Multiplatform)
Emulator `myhealth_api35`, date 4 Oct 2026. Release build (R8). Started from the published **v0.9.2** APK: onboarding, the owner's 0.9.2 backup imported with **Replace**, 18 screens captured as text (each from a fresh launch, scrolled to the end, union of all texts), then upgraded in place to 0.9.3 and captured again.

| Step | Result | Evidence |
|---|---|---|
| `bash tools/verify.sh` | PASS | 1093 unit tests (2 corpus tests skipped), lint clean, debug 91.0 MB, release 13.9 MB |
| `bash tools/connected.sh emulator-5554` | PASS | 26/26 |
| String oracle (one-off instrumented test, before `strings.xml` was trimmed) | PASS | all 1003 strings and 99 format strings × 2 argument sets identical to `Resources.getString` |
| 18 screens v0.9.2 → 0.9.3 (Today, Calendar, Nutrition, Training, More, Activities, Body & Health, Running PRs, Bike & power, Zones, Strength, Exercises, Load, Ingredients, Templates, Goals, Import, Settings) | PASS | 17 text-identical; Exercises differed only at the scroll window's edge — a full scroll on 0.9.3 (153 texts) contains every 0.9.2 name. Today's nutrition target and load values identical on both |
| Export on 0.9.2 and on 0.9.3 → JSON diff | PASS | only `appVersion`, `exportedAtMillis` and `dailyLoad.computedAtMillis` |
| 0.9.3: import the 0.9.2 export (Replace, new document opener) → export → diff | PASS | same three fields only |
| FIT import via the picker (`run_5k.fit`) and via an ACTION_VIEW intent (`ride_power.fit`); CSV `garmin_de.csv` via ACTION_VIEW | PASS | 1/1, 1/1, 3/3 saved; names and "FIT file"/"Garmin CSV" in history (`p203_import_fit.png`) |
| Health Connect: permission sheet through `PlatformUi` → all granted, additional access sheet, Sync now | PASS | permissions show Granted; "Last synced 23:09" |
| "Open Health Connect settings" | **FOUND → FIXED** | BUG-20 (since P2): the button did nothing on Android 14+ — `androidx.health.ACTION_HEALTH_CONNECT_SETTINGS` only resolves to the old Health Connect APK (`am start` on the emulator: "unable to resolve"). Fix: `android.health.connect.action.HEALTH_HOME_SETTINGS` from API 34, as `HealthConnectClient.ACTION_HEALTH_CONNECT_SETTINGS`. Rebuilt 0.9.3: the button opens Health Connect |
| Scan screen via the platform slot: camera permission, preview, Barcode/Label tabs | PASS | `p203_scan_camera.png` |
| Label "From photo" (bundled-model one-off build, NOTE-8) → "Check the scan" with the captured image (`loadHalfSizeImage`) → Accept | PASS | Calories 373, per 100 g, per-serving toggle (`p203_ocr_review.png`); shipped build: "Text recognition model is still downloading" + Retry, as in session 3 |
| Manual Open Food Facts lookup 3017620422003 | PASS | Nutella filled in |
| Settings → "Use wallpaper colours" on/off | PASS | nav bar colour switches to the wallpaper scheme and back to green |
| Exercise detail animation (Barbell back squat) | PASS | moves at animator scale 1, still pose at scale 0 (reduced motion) |
| Calendar month swipes; date picker in New event | PASS | Oct → Nov → Sep; picked 15 Oct → field 2026-10-15 (`p203_event_date.png`) |
| logcat | PASS | no FATAL / ANR for `com.myhealth` |


## Session 25 — 2026-10-05 (P21: iOS shell on the simulator via GitHub Actions; Android regression on the emulator)
iOS: `macos-26` runner, Xcode 26, iPhone simulator (iOS 26), debug build of the `Shared` framework + Swift shell, fresh install per run; synthetic onboarding values only. Android: emulator `myhealth_api35`, debug build.

| Step | Result | Evidence |
|---|---|---|
| iOS run 1 (Xcode 16.4) | **FAIL → fixed**: Kotlin framework built, link failed on `UIViewLayoutRegion` (CMP 1.11 needs the iOS 26 SDK) → runner `macos-26` + newest Xcode 26 | run log |
| iOS fresh launch → onboarding step 1, green theme, same layout as Android | PASS | `p21_ios_01_onboarding.png` |
| Name typed; birth date picker → text input → 01/15/1992 → OK | PASS | `p21_ios_04_date_typed.png` |
| After the date dialog the keyboard returned and covered "Next" (no back key on iPhone, no return key on the number pad) | **FOUND → fixed** (iOS shell: content above the keyboard, tap outside clears focus); re-run: "Next" above the number pad, tap on the title closes the keyboard | `p21_ios_07_step2_filled.png`, `p21_ios_07b_keyboard_closed.png` |
| Height 175, weight 72 → Next → Finish → Today: "0 / 2230 kcal", protein 115 g, carbs 260 g, fat 82 g — identical to Android with the same inputs | PASS | `p21_ios_09_today.png` |
| Calendar (October 2026, today marked), Training (5–11 Oct, Base, Generate suggestions), More (all entries) | PASS | `p21_ios_10_tab_*.png` |
| App terminated and relaunched → Today directly, same target (Room on bundled SQLite + DataStore persisted) | PASS | `p21_ios_11_relaunch_today.png` |
| iOS app log / crash reports | PASS | no exceptions, no crash report |
| Android: `bash tools/verify.sh` | PASS | 1101 unit tests (+8 `InProcessSyncSchedulerTest`), lint clean, release APK 13.9 MB |
| Android: `bash tools/connected.sh emulator-5554` | PASS | 26/26 |
| Android: launch on the `CoreGraph`-based `AppGraph` → onboarding → Today "0 / 2230 kcal"; start-up `LoadRecomputeWorker` SUCCESS; no FATAL | PASS | logcat (HC sync "permission denied" is expected after the instrumented run reset the app) |

- NOTE-27: iOS still has no HealthKit, camera scanner, document pickers or background tasks — the Health status shows "unavailable", "Sync now" does nothing and the scanner is a placeholder (P22).

## Session 26 — 2026-10-05 (P22: iOS platform layer on the simulator via GitHub Actions; Android regression on the emulator)
iOS: `macos-26` runner, Xcode 26, iPhone simulator (iOS 26), debug build, fresh install per run. Health data comes from the debug `HealthKitSeeder` (the same synthetic 45-day fixture set as `tools/hc-seeder`); the scan photos are the repo test assets (`docs/testassets/label_haferflocken_de.png`, `barcode_3017620422003.png`) added with `simctl addmedia`; `run_5k.fit` is placed in Files › On My iPhone. All four XCUITests green in run 37338210943.

| Step | Result | Evidence |
|---|---|---|
| Seeder writes the fixture set → Integrations → "Connect Apple Health" → permission sheet "Turn On All" → Allow | PASS | `p22_ios_01_integrations.png` |
| Sync: workouts, daily activity, sleep, body measurements all report a sync time; "Backfill history" runs | PASS | `p22_ios_02_synced.png` |
| Activities: seeded runs/soccer listed with duration, distance, avg HR, TRIMP and the Apple Health badge; Soccer chip present | PASS | `p22_ios_03_activities.png` |
| Run detail: 9.45 km, 52 min, avg pace 5:28 /km, HR curve from HR samples, load 98.8 (HR samples) | PASS | `p22_ios_04_activity_detail_hr.png` |
| Load & Recovery from the synced history: ATL 38, CTL 50, ACWR 0.75, chart over 28 days | PASS | `p22_ios_05_load_recovery.png` |
| Today after sync: recovery 75/100, "Last synced", nutrition targets | PASS | `p22_ios_06_today_after_sync.png` |
| iOS More screens had no way back (no back key on iPhone) | **FOUND → fixed**: `BackBar` with a back arrow on iOS only | all `p22_ios_*` More screens |
| Scan without a camera: "No camera" state, only "From photo" offered, no permission prompt | PASS | `p22_ios_07_scan_no_camera.png` |
| Label photo → Vision OCR: first run "No nutrition table was recognised" (Vision's default compute device fails in the simulator) | **FOUND → fixed**: CPU-only requests in the simulator, errors logged | run 37319020786 |
| Label OCR review: salt read as 100 (from "100% Vollkorn-Hafer"), saturated fat empty — Vision returns names and values as separate lines and they were sorted by top only | **FOUND → fixed**: lines go through the shared `OcrLineMapper` row order; JVM test `ocr20` on the recorded lines; re-run: 373 kcal, 1560 kJ, fat 7.0, sat. fat 1.2, carbs 58.7, sugar 1.1, fiber 10.0, protein 13.5, salt 0.02 | `p22_ios_08_ocr_review_values.png` |
| App crashed after OCR when logging the lines (`NSLog("%@", kotlinString)`; Kotlin/Native passes a C string) | **FOUND → fixed**: `%s` everywhere, including `PlatformLog.ios.kt` (every iOS warning would have crashed) | crash report `EXC_BAD_ACCESS` at an ASCII address |
| Barcode photo: Vision's default detector (and revisions 4, 3) found nothing in the simulator | **FOUND → fixed**: fallback through the detector revisions (revision 2 finds it) and a white-margin copy | app log |
| Barcode 3017620422003 → Open Food Facts → "New ingredient" Nutella with the barcode filled in | PASS | `p22_ios_09_barcode_result.png` |
| Import → Choose file → Files › On My iPhone › `run_5k.fit` → "1 parsed · 1 saved · 0 duplicates · 0 errors" | PASS | `p22_ios_10_fit_imported.png` |
| Backup → Export → saved to On My iPhone: 185 rows over 12 tables | PASS | `p22_ios_11_exported.png` |
| Backup → Import the exported file (merge): 185 rows in the file, 0 merged (nothing new) | PASS | `p22_ios_12_backup_imported_counts.png` |
| Android: `bash tools/verify.sh` | PASS | 1111 unit tests (+`ips12`, `ocr20`), lint clean, release APK 13.9 MB |
| Android: `bash tools/connected.sh emulator-5554` | PASS | 26/26 (More screens unchanged: `BackBar` shows only the content where the platform has a back key) |
| Android: debug build launched on the emulator → onboarding; no FATAL | PASS | logcat |

- NOTE-28: `BGAppRefreshTask` and HealthKit background delivery are wired (`IosBackground`, `ips12` covers the awaited sync + recompute) but iOS decides when they fire; the simulator run cannot show them. To be checked on a real iPhone in P23 (new Garmin run appears without opening the app).
- NOTE-29: the live camera (preview, barcode metadata output, capture, torch) needs a device; the simulator covers the photo path, which shares the Vision and parser code.
