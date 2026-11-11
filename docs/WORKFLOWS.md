# MyHealth — user workflows

Every journey a user can take through the Android app, grouped by area. Each workflow has an ID,
the steps and the expected result; **Result** records the last full run (see the session named in
the header of that run in `docs/VERIFICATION.md`). The iOS build shares every screen; its own
platform workflows (Apple Health, Files, scanner) are listed at the end.

Legend: PASS · FAIL (with a BUG/NOTE id) · PART (works, with a finding) · N/T (cannot be run on the
test setup — reason given).

Last full run: **0.9.5**, VERIFICATION session 30 (emulator, synthetic data; iOS rows from the simulator CI of session 29):
156 workflows — 118 PASS, 28 PART, 6 FAIL (BUG-24, BUG-25, BUG-26, BUG-27, BUG-29, BUG-30), 4 N/T. Open bugs:
BUG-23 to BUG-30; polish items POLISH-22 to POLISH-62. Session 30 added 39 workflows to the 117 of session 29.

**0.9.6** fixes BUG-23 to BUG-30 and most of the polish items; VERIFICATION session 31 lists what was
fixed, what was left as designed and what is still open. Session 31 re-checked the fixed items only —
the Result column below is still the 0.9.5 run of session 30.

## 1. First start

| ID | Workflow | Steps | Expected | Result |
|---|---|---|---|---|
| ON1 | Onboarding validation | Fresh install → Continue with empty or out-of-range fields (name, birth date, height, weight, goal pace) | A field error per field, no step change | PASS |
| ON2 | Complete onboarding | Fill About you → Body & goals → Preferences (weekly sessions per sport, rides, mobility, sleep target) → finish | Health Connect prompt is offered; app lands on Today without a crash; Settings shows the entered profile | PART — POLISH-22 |
| ON3 | Relaunch after onboarding | Close and reopen the app | Starts on Today, onboarding not shown again | PASS |
| ON4 | Go back in onboarding | Step 2 or 3 → Back → Next | Earlier input is kept | PASS |
| ON5 | Female profile | Onboard with sex Female | Cycle entry in More and cycle card on Today without touching Settings | PASS |

## 2. Today

| ID | Workflow | Steps | Expected | Result |
|---|---|---|---|---|
| TD1 | Read the dashboard | Open Today | Plan, nutrition, recovery, training load, today's activities, body, sleep and (if enabled) cycle cards show values or a clear empty state | PASS |
| TD2 | Sync from Today | Tap Sync now | Sync runs; new Health Connect data appears; a failure shows "Sync failed: …" | PASS |
| TD3 | Open sections | Tap the nutrition card, the plan card, the load card, an activity, Day detail | Each opens its screen (diary, Training/review, Load, activity detail, Day detail) | PASS (POLISH-23) |
| TD4 | Suggested activity links | With an activity on a day with a matching event, accept or dismiss the suggested link | Accept links the activity to the event; dismiss hides the suggestion | PART — POLISH-25, POLISH-26, POLISH-48 |
| TD5 | Plan card actions | Today's planned session → Done / Review | Done marks it done; Review opens the suggestion review | PART — Done works; Review not shown in the tested state |
| TD6 | Recovery and cycle cards | Read the recovery card; tap the cycle card | Score with confidence or a clear empty state; cycle card opens Cycle | PASS |
| TD7 | Day rollover | Leave the app in memory past midnight, reopen | Today shows the new day: diary total, plan, cycle day | FAIL — BUG-30 |

## 3. Calendar and events

| ID | Workflow | Steps | Expected | Result |
|---|---|---|---|---|
| CA1 | Browse the calendar | Month ↔ Week, previous/next, Jump to today | Days show dots for events, planned sessions, activities, meals | PASS |
| CA2 | Create a single event | New event → type, title, date, start time, duration, location, key event → Save | Event appears on its day in Calendar and Day detail | PASS |
| CA3 | Create a recurring event | New event → weekly on two weekdays, end date → Save | Every occurrence is shown until the end date | PART — BUG-23 |
| CA4 | Event validation | End date before start date → Save | "The end date must be on or after the start date." | PASS (POLISH-47) |
| CA5 | Edit an event | Open an event → change title/time → Save | Change shows everywhere | PASS |
| CA6 | Delete one occurrence | Recurring event → Delete → This occurrence | Only that day loses the event | PART — POLISH-24 |
| CA7 | Delete a series | Recurring event → Delete → Whole series | All occurrences removed, "Event deleted." | PASS |
| CA8 | All-day event | New event → All day on → Save | Shown as "All day", no start time | PASS |
| CA9 | Race event | Type race → target distance (and empty distance) → Save | Saved with distance; empty distance blocked with a visible error | PART — POLISH-31 |
| CA10 | Repeat every N weeks | Weekly, repeat every 2 weeks, no end date | Occurrences every second week, open-ended | PASS |
| CA11 | Add from a selected day | Select a day → New event; tap an activity in the day's agenda | Date is prefilled with the selected day; activity opens its detail | PASS |

## 4. Day detail

| ID | Workflow | Steps | Expected | Result |
|---|---|---|---|---|
| DD1 | Read a day | Calendar → a day | Events, planned sessions, activities, meals, sleep, targets vs intake, load; previous/next day | PASS |
| DD2 | Link an activity to an event | Event → Link activity → pick → (Manage link → Unlink) | "Activity linked." / "Activity unlinked."; the activity shows the linked event | PASS |
| DD3 | Mark a planned session done / skipped | Planned session → Mark done / Skip | Status changes, "Session marked …" | PASS |
| DD4 | Open from the day | Tap a meal, an activity, an event | Opens diary for that day, activity detail, event edit | PART — POLISH-45, POLISH-58 |
| DD5 | Sleep and cycle on a day | Open a day with synced sleep; with cycle tracking on | Sleep stages and score; cycle phase (predicted marked as such) | PASS |

## 5. Training plan and suggestions

| ID | Workflow | Steps | Expected | Result |
|---|---|---|---|---|
| TR1 | Generate a week | Training → Generate suggestions | Review shows a proposal with a phase, target load, rationale per session and rest days | PASS (POLISH-27) |
| TR2 | Accept a proposal | Review → select/deselect → Accept selected | Accepted sessions appear in Training and Calendar | PASS |
| TR3 | Regenerate | Training → Regenerate (also after a calendar change: "Calendar changed — regenerate") | New proposal replaces the earlier suggested sessions; locked and hand-planned sessions stay | FAIL — BUG-24 |
| TR4 | Add a session by hand | + Session → sport, type, intensity, duration, distance, start time, lock → Save | Session on its day, shown as locked when locked | PASS |
| TR5 | Edit / delete a session | Session → Edit → change → Save; Delete | Change saved; "Session deleted." | PASS (POLISH-50, POLISH-49) |
| TR6 | Done / skip / reopen | Session → Mark done, Skip, Reopen | Status changes with a message | PASS |
| TR7 | Lock / unlock | Session → lock toggle | "Session locked." / "Session unlocked."; regenerate keeps it | PASS |
| TR8 | Browse weeks | Previous week / Next week | Planned vs target vs actual per week; phase badge only from a covering batch | PASS (POLISH-41) |
| TR9 | Session steps | An interval / structured session → Show steps / Hide steps | Warm-up, repeats with recovery, cool-down with targets | PART — in a recovery week the interval run has no steps by design (POLISH-62); not reachable in a build week on this data |
| TR10 | Select in the review | Review → deselect one, Select all | "N of M selected · X AU" follows; only selected sessions are accepted | PASS |
| TR11 | Activity fulfils a planned session | Planned session on a day, then a matching synced activity | Session shows as linked/done with the actual load in the week totals | PASS |
| TR12 | Session targets | + Session run with distance and pace target; strength session with a workout picked | Targets and workout name shown on the session; estimated load | PASS |

## 6. Strength and mobility

| ID | Workflow | Steps | Expected | Result |
|---|---|---|---|---|
| ST1 | Browse exercises | Exercises → search, equipment/pattern/muscle filters, tap a muscle on the body figure, Clear filters | List narrows accordingly | PASS |
| ST2 | Exercise detail | Open an exercise → animation plays; tap to pause; muscles on the figure | Figure with primary/secondary muscles, equipment, cue | PASS |
| ST3 | Add an exercise to a workout | Exercise detail → Add to workout… → pick | "Added to …" | PASS |
| ST4 | Create a workout | Strength workouts → New workout → name, kind, Add exercise…, sets/reps or seconds, load or bodyweight, rest, note, reorder, remove → Save | Workout listed with exercise count and minutes | PASS |
| ST5 | Workout validation | Save without name / a row without sets / reps and seconds both set | Field errors, no save | PART — POLISH-28 |
| ST6 | Duplicate / delete a workout | More actions → Duplicate; Delete | Copy appears; deleted workout gone, planned sessions keep their record | PART — POLISH-30 |
| ST7 | Plan a workout for a day | More actions → Plan for a day… | "Workout planned."; session on that day in Training | PASS (POLISH-29) |
| ST8 | Log sets with feedback | Training → strength session → Mark done → log sets, feedback (too easy … too hard) → Finish; or Skip logging | Session done; next time's load/reps progress ("Next time: …") | PASS (POLISH-51) |
| ST9 | Use in suggestions | Workouts → toggle "Use in suggestions" off | Workout no longer picked by the suggester | PASS |
| ST10 | Mobility on rest days | Settings → Mobility on rest days on → generate | Rest days carry a mobility routine chosen from muscle load | PASS |
| ST11 | My equipment | Settings → Strength → select equipment | Exercises/workouts only use the selection; "Show all equipment" lifts the filter | PART — POLISH-53 |
| ST12 | Exercise kinds and equipment toggle | Exercises → All / Strength / Mobility; Only my equipment | List narrows; mobility rows marked | PASS |
| ST13 | Progression on an exercise | After a logged session open the exercise | Progression section with the last feedback and date | PASS |
| ST14 | Mobility session | A planned mobility routine → open, read exercises, Mark done | Routine name and exercises shown; done without set logging or with its own log | PASS |

## 7. Goals

| ID | Workflow | Steps | Expected | Result |
|---|---|---|---|---|
| GO1 | Race goal | New goal → race time, distance, target time, date, race on this date, link race event → Create | Listed with progress (current best vs target) | PASS |
| GO2 | Deadline goal | Race time goal with "Race on this date" off | Plan keeps building, no taper | PASS |
| GO3 | Other goal types | Body weight, strength lift, consistency, soccer availability, bike FTP, bike volume, bike event | Each form shows its own fields; saved goal lists with its progress | PASS |
| GO4 | Goal validation | Create without required fields | Field errors | PASS |
| GO5 | Goal status | Make primary, Achieved, Abandon, Reactivate | Status and primary marker change | PASS |
| GO6 | Edit / delete a goal | Open → change → Save; Delete | Saved; "Goal deleted." | PASS (POLISH-32) |

## 8. Activities

| ID | Workflow | Steps | Expected | Result |
|---|---|---|---|---|
| AC1 | List and filter | Activities → sport chips | Reverse-chronological list with source badges; filter narrows | PASS |
| AC2 | Activity detail | Open an activity | Overview, HR/pace/speed/altitude/power charts or empty states, zones Z1–Z5, laps, TRIMP | PASS |
| AC3 | Edit an activity | Title, notes, RPE | Saved and shown after reopening | PASS (POLISH-46) |
| AC4 | Link to an event | Link to event… → pick; Change; Unlink | Linked event shown / removed | PASS |
| AC5 | Delete an activity | Delete activity → confirm | Gone from list, load recomputed | FAIL — BUG-25 |
| AC6 | Empty state | Activities on a fresh install → Sync now | Empty message; Sync now syncs or reports the missing permission | PASS (POLISH-22) |

## 9. Performance screens

| ID | Workflow | Steps | Expected | Result |
|---|---|---|---|---|
| PF1 | Running PRs | More → Running PRs | Personal bests per distance, Riegel predictions, progression; tap → activity | PASS |
| PF2 | Manual PR | Add manual PR → distance, minutes, seconds, date | PR appears (estimated badge where relevant) | PASS |
| PF3 | Bike & power | More → Bike & power | FTP card (or hint), power and time bests, links to rides | PASS |
| PF4 | Zones & paces | More → Zones & paces | Five zones with bpm, scheme, pace bands, Daniels paces, polarisation | PART — POLISH-33, POLISH-34, POLISH-54 |
| PF5 | Load & recovery | More → Load & Recovery → 28d/90d/365d | ATL/CTL/TSB, ACWR, daily TRIMP, recovery history, muscle load card | PASS (POLISH-35) |

## 10. Nutrition diary and water

| ID | Workflow | Steps | Expected | Result |
|---|---|---|---|---|
| NU1 | Read a day's diary | Nutrition → previous/next day | Target header, meals per slot, totals, water | PART — BUG-28 |
| NU2 | Why this target? | Expand the explanation | BMR, TDEE source, day type, goal, macro reasoning | PASS (POLISH-37) |
| NU3 | Day type follows the calendar | Add a match tomorrow → open tomorrow | Day type pre-match: carbs up, fat down, no deficit | PART — POLISH-36 |
| NU4 | Edit / remove a logged item | Item actions → change quantity; remove; Meal actions → remove meal | Totals update; "Item removed." / "Meal removed." | PASS |
| NU5 | Copy yesterday | Copy yesterday's meals | Yesterday's meals appear today with the same numbers | PASS |
| NU6 | Water | +250/+500 ml, Custom (and invalid 0), delete a drink | Total updates; validation for 0; drink removed | PASS (POLISH-45) |
| NU7 | Log on another day | Open a past and a future day → add food | Item lands on that day, not today | PASS |
| NU8 | Training day vs rest day | Compare a day with a planned session and a rest day | Target and day type differ and the explanation says why | PART — POLISH-36 |

## 11. Add food

| ID | Workflow | Steps | Expected | Result |
|---|---|---|---|---|
| AF1 | Search and add | Meal slot + Add → Search → ingredient → quantity + unit → Add | Live macro preview; item in that slot | PASS |
| AF2 | Recents / Favorites | Recents and Favorites tabs | Recently logged / starred ingredients listed | PASS |
| AF3 | Template with servings | Templates tab → template → Servings eaten → Log | Scaled items in that slot | PASS |
| AF4 | New ingredient from Add food | + (New ingredient) → save → back | Ingredient available to add | PASS |
| AF5 | Scan from Add food | Search tab → Scan | Opens the scanner | PASS |
| AF6 | Units and quantity validation | Add with g, piece and serving units; quantity 0 or empty | Preview converts per unit; "Enter a quantity above zero." | PASS |

## 12. Ingredients, scanning and lookup

| ID | Workflow | Steps | Expected | Result |
|---|---|---|---|---|
| IN1 | Create an ingredient | New ingredient → name, brand, basis (per 100 g / 100 ml / piece), piece weight, default serving, macros, more nutrients → Save | Listed with kcal/100; sodium derived from salt | PASS |
| IN2 | Ingredient validation | Missing name / implausible values | Field errors or warnings | PASS (POLISH-55, POLISH-45) |
| IN3 | Edit / favorite an ingredient | Change values, star | Saved; appears under Favorites | PASS |
| IN4 | Delete an ingredient | Unused → deleted; used in a meal or template → archived | Matches the dialog text | PASS |
| IN5 | Search ingredients | Ingredients search | List filters | PASS (POLISH-45) |
| IN6 | Barcode lookup in the editor | Enter a barcode → Look up barcode | Open Food Facts fills the form; unknown code → clean error | PASS |
| IN7 | Label OCR from a photo | Scan → From photo → a nutrition label → review → Accept | Fields prefilled with confidence; nothing saved until Accept → editor | N/T — text-recognition model cannot download on the emulator |
| IN8 | Barcode from a photo | Scan → Barcode → From photo | Lookup → prefilled editor | N/T — same |
| IN9 | Live camera | Scan → Allow camera → preview, Capture label | Preview runs; capture goes to review or a clean "nothing recognised" | PART — screen and mode switch only (no camera feed on the emulator) |
| IN10 | Enter manually from the scanner | Scan → Enter it manually | Empty editor | N/T — needs a decoded barcode |
| IN11 | Scan from the ingredient editor | Ingredient editor → Scan | Opens the scanner; result returns to the editor | PASS |
| IN12 | Camera permission denied | Scan → deny camera | "Camera access is off" with Allow camera / Open settings; From photo still offered | PART — POLISH-56 |
| IN13 | Review a scan | Check the scan → edit a value, per-serving column, Retake | Edited values carried to the editor; Retake returns to the scanner | N/T — needs a recognised label |
| IN14 | Archived ingredients | Ingredients → Show archived | Archived list; archived items not offered in Add food | PART — POLISH-57 |

## 13. Meal templates

| ID | Workflow | Steps | Expected | Result |
|---|---|---|---|---|
| MT1 | Create a template | New template → name, servings, default slot, note, add ingredients + quantities → Save | Listed with per-serving macros | PASS |
| MT2 | Edit a template (BUG-22) | Open → change name, servings, quantities, add/remove an item → Save | Saved, list updated | PASS (BUG-22) |
| MT3 | Favorite a template | Star on the list | Sticks, items kept | PASS |
| MT4 | Log now with servings | Log now → date, slot, servings eaten → Log | Scaled items in the diary; 0 servings blocked | PASS |
| MT5 | Delete a template | Delete template → confirm | Gone; meals logged from it keep their items | PASS |
| MT6 | Template validation | No name / no items / servings 0 | Field errors | PASS |
| MT7 | Template with a removed ingredient | Archive/delete an ingredient used in a template → open and log the template | Template marks the missing item and still logs the rest | PASS |

## 14. Body and health

| ID | Workflow | Steps | Expected | Result |
|---|---|---|---|---|
| BO1 | Log weight and body fat | Body & Health → Log weight → weight, body fat | Current weight, chart, 7-day average, goal distance | PASS |
| BO2 | Chart ranges | 30d / 90d / 365d | Charts rescale | PART — POLISH-39 |
| BO3 | Synced health data | After a sync | Resting HR chart and sleep bars from Health Connect | PASS |
| BO4 | Correct a weight | Log a second weight on the same day | Latest value counts; no duplicate point | PASS |

## 15. Cycle

| ID | Workflow | Steps | Expected | Result |
|---|---|---|---|---|
| CY1 | Enable tracking | Settings → Track menstrual cycle | Cycle entry in More, cycle card on Today | PASS |
| CY2 | Log a period | Log period start → Period ended | Status, phase, forecast of six cycles, history | PASS |
| CY3 | Cycle validation | Second start on the same day; end before start | Clear errors | PASS |
| CY4 | Delete an entry | History → delete | Entry removed | PASS |
| CY5 | Cycle across the app | With a logged period: Today, Calendar, Day detail, Training proposal | Phase on Today, markers on calendar days, phase in Day detail; the plan rationale names the phase when a cycle rule applies | PART — Today, Calendar, Day detail, plan rationale; nutrition does not use the cycle |

## 16. Import

| ID | Workflow | Steps | Expected | Result |
|---|---|---|---|---|
| IM1 | Import a FIT file | Import → Choose file → `.fit` | "Import finished" summary; activity in the list with the FIT badge | PASS |
| IM2 | Duplicate file | Import the same file again | "Already imported"; Import anyway / Dismiss | PASS (POLISH-59) |
| IM3 | Undo an import | History row → More actions → Undo import | Activities only from that file removed; file can be imported again | PART — POLISH-59 |
| IM4 | Import a CSV / ZIP | Choose a Garmin CSV or export ZIP | Summary with parsed / inserted / duplicates | PASS |
| IM5 | Share sheet / open with | Share a `.fit`/`.zip`/`.csv` to MyHealth, or open a `.fit` with it | Import screen runs the file | FAIL — BUG-26 (.fit); .csv share PASS |
| IM6 | Unsupported or broken file | Choose a `.txt`; a truncated `.fit` | "… is not a .fit, .csv or .zip file." / a clean failure, no crash, nothing saved | PASS |
| IM7 | Import history | After several imports | Rows with kind, date and counts; newest first | PASS |

## 17. Backup

| ID | Workflow | Steps | Expected | Result |
|---|---|---|---|---|
| BK1 | Export | Backup → Export backup → file name | "N rows over M tables written", schema and app version | PASS |
| BK2 | Import, merge | Import backup with Merge | Only missing rows added | PASS |
| BK3 | Import, replace | Import backup with Replace | Device data equals the file | PASS (POLISH-40) |
| BK4 | Wrong file as backup | Import backup → a non-backup JSON / other file | "That file is not a MyHealth backup …", nothing changed | FAIL — BUG-29, POLISH-40 |
| BK5 | Backup from the previous release | Import a backup written by the previous schema | Imported and migrated, no error | PASS |

## 18. Health Connect

| ID | Workflow | Steps | Expected | Result |
|---|---|---|---|---|
| HC1 | Status and permissions | Integrations → Grant permissions → Allow all | Status "Available", permissions Granted | PASS |
| HC2 | Sync now | Integrations → Sync now | Last sync time; activities, sleep, resting HR, weight arrive | PASS |
| HC3 | Backfill | Backfill from a date → Start backfill | "Backfilled back to …" | PASS |
| HC4 | Open Health Connect settings | Open Health Connect settings | System HC screen for MyHealth | PASS |
| HC5 | Privacy screen from Health Connect | HC → MyHealth → privacy policy | MyHealth's permission rationale screen | PASS |
| HC6 | Permission revoked | Revoke a permission in Health Connect → return, sync | Row shows Not granted; sync reports it; Grant permissions offered again | PART — works; message does not name the permission |
| HC7 | Optional power permission | Grant cycling power later | Recent rides are re-read with power; nothing duplicated | PASS (BUG-25) |
| HC8 | New data arrives | New workout and weight in Health Connect → Sync now | Activity, load and weight update | PASS |

## 19. Settings

| ID | Workflow | Steps | Expected | Result |
|---|---|---|---|---|
| SE1 | Edit the profile | Name, sex, birth date, height, goal weight, goal pace, activity level, HR overrides, sleep target | Saved; nutrition target follows | PART — BUG-27 |
| SE2 | Weekly caps | Sessions / week per sport, rides / week | Suggestions respect the caps | PART — POLISH-41, POLISH-42 |
| SE3 | Heart-rate zones | Scheme, LTHR, four bounds with preview; invalid order | Preview updates; ascending check | PASS (POLISH-54) |
| SE4 | Cycling | FTP override, indoor trainer | Bike & power and suggestions use them | PASS |
| SE5 | App preferences | Theme, wallpaper colours, sync interval, suggestion horizon, treadmill in PRs, Open Food Facts contact | Applied immediately / on next sync | FAIL — BUG-27; POLISH-43 (in part) |
| SE6 | Maintenance | Remove orphaned import data; Recompute training load | Confirmations and result messages | PASS |
| SE7 | Garmin direct (experimental) | Toggle and open | Screen explains the experimental client; nothing breaks | PART — POLISH-44 |
| SE8 | HR overrides | Resting HR and max HR override → Zones & paces | Zones recomputed from the overrides; cleared → measured values | PART — BUG-27 (override fields) |

## 20. Across the app

| ID | Workflow | Steps | Expected | Result |
|---|---|---|---|---|
| X1 | Bottom navigation and Back | Switch tabs, go deep, Back | Tabs keep their state; Back returns step by step | PASS |
| X2 | Rotation | Rotate on the main screens and editors | No crash, no lost input | PASS |
| X3 | Offline | Airplane mode → use the app, try a barcode lookup | Everything works except the lookup, which fails cleanly | PASS |
| X4 | Dark theme | System dark mode | All screens readable | PASS |
| X5 | Process death | Kill the app in the background, reopen | No crash; data intact | PASS |
| X6 | Background sync | Periodic worker | Last sync time advances without opening the app | PASS |
| X7 | German device locale | System language Deutsch (Deutschland): enter decimals with a comma, read numbers and dates | Comma input accepted; consistent formats; app text stays English | PART — POLISH-60 |
| X8 | Large font | System font size at maximum | No clipped or overlapping controls on the main screens | PART — POLISH-61 |
| X9 | Update over the previous release | Install the previous release, add data, install the current APK over it | Data and settings kept, database migrated, no crash | PASS |
| X10 | After a reboot | Reboot the device, do not open the app | Periodic sync is still scheduled and runs | PASS |

## 21. iPhone (shared screens + iOS platform layer)

| ID | Workflow | Steps | Expected | Result |
|---|---|---|---|---|
| IO1 | Apple Health sync | Connect Apple Health → sync | Workouts, HR, sleep, weight in the app | PASS (simulator CI, not rerun) |
| IO2 | Files import / backup | Import a FIT from Files; export/import a backup | Same as IM1 / BK1–BK3 | PASS (simulator CI, not rerun) |
| IO3 | Scanner | Label photo and barcode photo | Same as IN7 / IN8 | PASS (simulator CI, not rerun) |
