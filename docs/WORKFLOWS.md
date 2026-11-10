# MyHealth — user workflows

Every journey a user can take through the Android app, grouped by area. Each workflow has an ID,
the steps and the expected result; **Result** records the last full run (see the session named in
the header of that run in `docs/VERIFICATION.md`). The iOS build shares every screen; its own
platform workflows (Apple Health, Files, scanner) are listed at the end.

Legend: PASS · FAIL (with a BUG/NOTE id) · PART (works, with a finding) · N/T (cannot be run on the
test setup — reason given).

Last full run: **0.9.5**, VERIFICATION session 29 (emulator, synthetic data; iOS rows from the simulator CI):
117 workflows — 95 PASS, 15 PART, 4 FAIL (BUG-24, BUG-25, BUG-26, BUG-27), 3 N/T. Open bugs from the run:
BUG-23 to BUG-28; polish items POLISH-22 to POLISH-45.

## 1. First start

| ID | Workflow | Steps | Expected | Result |
|---|---|---|---|---|
| ON1 | Onboarding validation | Fresh install → Continue with empty or out-of-range fields (name, birth date, height, weight, goal pace) | A field error per field, no step change | PASS |
| ON2 | Complete onboarding | Fill About you → Body & goals → Preferences (weekly sessions per sport, rides, mobility, sleep target) → finish | Health Connect prompt is offered; app lands on Today without a crash; Settings shows the entered profile | PART — POLISH-22 |
| ON3 | Relaunch after onboarding | Close and reopen the app | Starts on Today, onboarding not shown again | PASS |

## 2. Today

| ID | Workflow | Steps | Expected | Result |
|---|---|---|---|---|
| TD1 | Read the dashboard | Open Today | Plan, nutrition, recovery, training load, today's activities, body, sleep and (if enabled) cycle cards show values or a clear empty state | PASS |
| TD2 | Sync from Today | Tap Sync now | Sync runs; new Health Connect data appears; a failure shows "Sync failed: …" | PASS |
| TD3 | Open sections | Tap the nutrition card, the plan card, the load card, an activity, Day detail | Each opens its screen (diary, Training/review, Load, activity detail, Day detail) | PASS (POLISH-23) |
| TD4 | Suggested activity links | With an activity on a day with a matching event, accept or dismiss the suggested link | Accept links the activity to the event; dismiss hides the suggestion | PART — POLISH-25, POLISH-26 |
| TD5 | Plan card actions | Today's planned session → Done / Review | Done marks it done; Review opens the suggestion review | PART — Done works; Review not shown in the tested state |

## 3. Calendar and events

| ID | Workflow | Steps | Expected | Result |
|---|---|---|---|---|
| CA1 | Browse the calendar | Month ↔ Week, previous/next, Jump to today | Days show dots for events, planned sessions, activities, meals | PASS |
| CA2 | Create a single event | New event → type, title, date, start time, duration, location, key event → Save | Event appears on its day in Calendar and Day detail | PASS |
| CA3 | Create a recurring event | New event → weekly on two weekdays, end date → Save | Every occurrence is shown until the end date | PART — BUG-23 |
| CA4 | Event validation | End date before start date → Save | "The end date must be on or after the start date." | PASS (POLISH-31) |
| CA5 | Edit an event | Open an event → change title/time → Save | Change shows everywhere | PASS |
| CA6 | Delete one occurrence | Recurring event → Delete → This occurrence | Only that day loses the event | PART — POLISH-24 |
| CA7 | Delete a series | Recurring event → Delete → Whole series | All occurrences removed, "Event deleted." | PASS |

## 4. Day detail

| ID | Workflow | Steps | Expected | Result |
|---|---|---|---|---|
| DD1 | Read a day | Calendar → a day | Events, planned sessions, activities, meals, sleep, targets vs intake, load; previous/next day | PASS |
| DD2 | Link an activity to an event | Event → Link activity → pick → (Manage link → Unlink) | "Activity linked." / "Activity unlinked."; the activity shows the linked event | PASS |
| DD3 | Mark a planned session done / skipped | Planned session → Mark done / Skip | Status changes, "Session marked …" | PASS |
| DD4 | Open from the day | Tap a meal, an activity, an event | Opens diary for that day, activity detail, event edit | PART — event rows not tappable (POLISH-45) |

## 5. Training plan and suggestions

| ID | Workflow | Steps | Expected | Result |
|---|---|---|---|---|
| TR1 | Generate a week | Training → Generate suggestions | Review shows a proposal with a phase, target load, rationale per session and rest days | PASS (POLISH-27) |
| TR2 | Accept a proposal | Review → select/deselect → Accept selected | Accepted sessions appear in Training and Calendar | PASS |
| TR3 | Regenerate | Training → Regenerate (also after a calendar change: "Calendar changed — regenerate") | New proposal replaces the earlier suggested sessions; locked and hand-planned sessions stay | FAIL — BUG-24 |
| TR4 | Add a session by hand | + Session → sport, type, intensity, duration, distance, start time, lock → Save | Session on its day, shown as locked when locked | PASS |
| TR5 | Edit / delete a session | Session → Edit → change → Save; Delete | Change saved; "Session deleted." | PASS |
| TR6 | Done / skip / reopen | Session → Mark done, Skip, Reopen | Status changes with a message | PASS |
| TR7 | Lock / unlock | Session → lock toggle | "Session locked." / "Session unlocked."; regenerate keeps it | PASS |
| TR8 | Browse weeks | Previous week / Next week | Planned vs target vs actual per week; phase badge only from a covering batch | PASS |

## 6. Strength and mobility

| ID | Workflow | Steps | Expected | Result |
|---|---|---|---|---|
| ST1 | Browse exercises | Exercises → search, equipment/pattern/muscle filters, tap a muscle on the body figure, Clear filters | List narrows accordingly | PASS |
| ST2 | Exercise detail | Open an exercise → animation plays; tap to pause; muscles on the figure | Figure with primary/secondary muscles, equipment, cue | PASS |
| ST3 | Add an exercise to a workout | Exercise detail → Add to workout… → pick | "Added to …" | PASS |
| ST4 | Create a workout | Strength workouts → New workout → name, kind, Add exercise…, sets/reps or seconds, load or bodyweight, rest, note, reorder, remove → Save | Workout listed with exercise count and minutes | PASS |
| ST5 | Workout validation | Save without name / a row without sets / reps and seconds both set | Field errors, no save | PART — POLISH-28 |
| ST6 | Duplicate / delete a workout | More actions → Duplicate; Delete | Copy appears; deleted workout gone, planned sessions keep their record | PASS (POLISH-30) |
| ST7 | Plan a workout for a day | More actions → Plan for a day… | "Workout planned."; session on that day in Training | PASS (POLISH-29) |
| ST8 | Log sets with feedback | Training → strength session → Mark done → log sets, feedback (too easy … too hard) → Finish; or Skip logging | Session done; next time's load/reps progress ("Next time: …") | PASS |
| ST9 | Use in suggestions | Workouts → toggle "Use in suggestions" off | Workout no longer picked by the suggester | PASS |
| ST10 | Mobility on rest days | Settings → Mobility on rest days on → generate | Rest days carry a mobility routine chosen from muscle load | PASS |
| ST11 | My equipment | Settings → Strength → select equipment | Exercises/workouts only use the selection; "Show all equipment" lifts the filter | PASS |

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
| AC3 | Edit an activity | Title, notes, RPE | Saved and shown after reopening | PASS |
| AC4 | Link to an event | Link to event… → pick; Change; Unlink | Linked event shown / removed | PASS |
| AC5 | Delete an activity | Delete activity → confirm | Gone from list, load recomputed | FAIL — BUG-25 |

## 9. Performance screens

| ID | Workflow | Steps | Expected | Result |
|---|---|---|---|---|
| PF1 | Running PRs | More → Running PRs | Personal bests per distance, Riegel predictions, progression; tap → activity | PASS |
| PF2 | Manual PR | Add manual PR → distance, minutes, seconds, date | PR appears (estimated badge where relevant) | PASS |
| PF3 | Bike & power | More → Bike & power | FTP card (or hint), power and time bests, links to rides | PASS |
| PF4 | Zones & paces | More → Zones & paces | Five zones with bpm, scheme, pace bands, Daniels paces, polarisation | PART — POLISH-33, POLISH-34 |
| PF5 | Load & recovery | More → Load & Recovery → 28d/90d/365d | ATL/CTL/TSB, ACWR, daily TRIMP, recovery history, muscle load card | PASS (POLISH-35, NOTE-36, NOTE-37) |

## 10. Nutrition diary and water

| ID | Workflow | Steps | Expected | Result |
|---|---|---|---|---|
| NU1 | Read a day's diary | Nutrition → previous/next day | Target header, meals per slot, totals, water | PART — BUG-28 |
| NU2 | Why this target? | Expand the explanation | BMR, TDEE source, day type, goal, macro reasoning | PASS (POLISH-37) |
| NU3 | Day type follows the calendar | Add a match tomorrow → open tomorrow | Day type pre-match: carbs up, fat down, no deficit | PART — POLISH-36 |
| NU4 | Edit / remove a logged item | Item actions → change quantity; remove; Meal actions → remove meal | Totals update; "Item removed." / "Meal removed." | PASS |
| NU5 | Copy yesterday | Copy yesterday's meals | Yesterday's meals appear today with the same numbers | PASS |
| NU6 | Water | +250/+500 ml, Custom (and invalid 0), delete a drink | Total updates; validation for 0; drink removed | PASS (0 ml silently ignored, POLISH-45) |

## 11. Add food

| ID | Workflow | Steps | Expected | Result |
|---|---|---|---|---|
| AF1 | Search and add | Meal slot + Add → Search → ingredient → quantity + unit → Add | Live macro preview; item in that slot | PASS |
| AF2 | Recents / Favorites | Recents and Favorites tabs | Recently logged / starred ingredients listed | PASS |
| AF3 | Template with servings | Templates tab → template → Servings eaten → Log | Scaled items in that slot | PASS |
| AF4 | New ingredient from Add food | + (New ingredient) → save → back | Ingredient available to add | PASS |
| AF5 | Scan from Add food | Search tab → Scan | Opens the scanner | PASS |

## 12. Ingredients, scanning and lookup

| ID | Workflow | Steps | Expected | Result |
|---|---|---|---|---|
| IN1 | Create an ingredient | New ingredient → name, brand, basis (per 100 g / 100 ml / piece), piece weight, default serving, macros, more nutrients → Save | Listed with kcal/100; sodium derived from salt | PASS |
| IN2 | Ingredient validation | Missing name / implausible values | Field errors or warnings | PASS (POLISH-45) |
| IN3 | Edit / favorite an ingredient | Change values, star | Saved; appears under Favorites | PASS |
| IN4 | Delete an ingredient | Unused → deleted; used in a meal or template → archived | Matches the dialog text | PASS |
| IN5 | Search ingredients | Ingredients search | List filters | PASS |
| IN6 | Barcode lookup in the editor | Enter a barcode → Look up barcode | Open Food Facts fills the form; unknown code → clean error | PASS |
| IN7 | Label OCR from a photo | Scan → From photo → a nutrition label → review → Accept | Fields prefilled with confidence; nothing saved until Accept → editor | N/T — the emulator image cannot download the ML Kit model (NOTE-6); POLISH-38 |
| IN8 | Barcode from a photo | Scan → Barcode → From photo | Lookup → prefilled editor | N/T — same as IN7 |
| IN9 | Live camera | Scan → Allow camera → preview, Capture label | Preview runs; capture goes to review or a clean "nothing recognised" | PART — no camera feed on the emulator; clean "model not ready" message |
| IN10 | Enter manually from the scanner | Scan → Enter it manually | Empty editor | N/T — needs a decoded barcode (IN8) |

## 13. Meal templates

| ID | Workflow | Steps | Expected | Result |
|---|---|---|---|---|
| MT1 | Create a template | New template → name, servings, default slot, note, add ingredients + quantities → Save | Listed with per-serving macros | PASS |
| MT2 | Edit a template (BUG-22) | Open → change name, servings, quantities, add/remove an item → Save | Saved, list updated | PASS |
| MT3 | Favorite a template | Star on the list | Sticks, items kept | PASS |
| MT4 | Log now with servings | Log now → slot, servings eaten → Log | Scaled items in the diary; 0 servings blocked | PASS |
| MT5 | Delete a template | Delete template → confirm | Gone; meals logged from it keep their items | PASS |
| MT6 | Template validation | No name / no items / servings 0 | Field errors | PASS |

## 14. Body and health

| ID | Workflow | Steps | Expected | Result |
|---|---|---|---|---|
| BO1 | Log weight and body fat | Body & Health → Log weight → weight, body fat | Current weight, chart, 7-day average, goal distance | PASS |
| BO2 | Chart ranges | 30d / 90d / 365d | Charts rescale | PART — POLISH-39 |
| BO3 | Synced health data | After a sync | Resting HR chart and sleep bars from Health Connect | PASS |

## 15. Cycle

| ID | Workflow | Steps | Expected | Result |
|---|---|---|---|---|
| CY1 | Enable tracking | Settings → Track menstrual cycle | Cycle entry in More, cycle card on Today | PASS |
| CY2 | Log a period | Log period start → Period ended | Status, phase, forecast of six cycles, history | PASS |
| CY3 | Cycle validation | Second start on the same day; end before start | Clear errors | PASS |
| CY4 | Delete an entry | History → delete | Entry removed | PASS |

## 16. Import

| ID | Workflow | Steps | Expected | Result |
|---|---|---|---|---|
| IM1 | Import a FIT file | Import → Choose file → `.fit` | "Import finished" summary; activity in the list with the FIT badge | PASS |
| IM2 | Duplicate file | Import the same file again | "Already imported"; Import anyway / Dismiss | PASS |
| IM3 | Undo an import | History row → More actions → Undo import | Activities only from that file removed; file can be imported again | PASS |
| IM4 | Import a CSV / ZIP | Choose a Garmin CSV or export ZIP | Summary with parsed / inserted / duplicates | PASS |
| IM5 | Share sheet / open with | Share a `.fit`/`.zip`/`.csv` to MyHealth, or open a `.fit` with it | Import screen runs the file | FAIL — BUG-26 (.fit); .csv share PASS |

## 17. Backup

| ID | Workflow | Steps | Expected | Result |
|---|---|---|---|---|
| BK1 | Export | Backup → Export backup → file name | "N rows over M tables written", schema and app version | PASS |
| BK2 | Import, merge | Import backup with Merge | Only missing rows added | PASS |
| BK3 | Import, replace | Import backup with Replace | Device data equals the file | PASS (POLISH-40) |

## 18. Health Connect

| ID | Workflow | Steps | Expected | Result |
|---|---|---|---|---|
| HC1 | Status and permissions | Integrations → Grant permissions → Allow all | Status "Available", permissions Granted | PASS |
| HC2 | Sync now | Integrations → Sync now | Last sync time; activities, sleep, resting HR, weight arrive | PASS |
| HC3 | Backfill | Backfill from a date → Start backfill | "Backfilled back to …" | PASS (NOTE-1) |
| HC4 | Open Health Connect settings | Open Health Connect settings | System HC screen for MyHealth | PASS |
| HC5 | Privacy screen from Health Connect | HC → MyHealth → privacy policy | MyHealth's permission rationale screen | PASS |

## 19. Settings

| ID | Workflow | Steps | Expected | Result |
|---|---|---|---|---|
| SE1 | Edit the profile | Name, sex, birth date, height, goal weight, goal pace, activity level, HR overrides, sleep target | Saved; nutrition target follows | PART — BUG-27 (Name field) |
| SE2 | Weekly caps | Sessions / week per sport, rides / week | Suggestions respect the caps | PART — POLISH-41, POLISH-42 |
| SE3 | Heart-rate zones | Scheme, LTHR, four bounds with preview; invalid order | Preview updates; ascending check | PASS |
| SE4 | Cycling | FTP override, indoor trainer | Bike & power and suggestions use them | PASS |
| SE5 | App preferences | Theme, wallpaper colours, sync interval, suggestion horizon, treadmill in PRs, Open Food Facts contact | Applied immediately / on next sync | FAIL — BUG-27; POLISH-43 |
| SE6 | Maintenance | Remove orphaned import data; Recompute training load | Confirmations and result messages | PASS |
| SE7 | Garmin direct (experimental) | Toggle and open | Screen explains the experimental client; nothing breaks | PART — POLISH-44 |

## 20. Across the app

| ID | Workflow | Steps | Expected | Result |
|---|---|---|---|---|
| X1 | Bottom navigation and Back | Switch tabs, go deep, Back | Tabs keep their state; Back returns step by step | PASS |
| X2 | Rotation | Rotate on the main screens and editors | No crash, no lost input | PASS |
| X3 | Offline | Airplane mode → use the app, try a barcode lookup | Everything works except the lookup, which fails cleanly | PASS |
| X4 | Dark theme | System dark mode | All screens readable | PASS |
| X5 | Process death | Kill the app in the background, reopen | No crash; data intact | PASS |
| X6 | Background sync | Periodic worker | Last sync time advances without opening the app | PASS |

## 21. iPhone (shared screens + iOS platform layer)

| ID | Workflow | Steps | Expected | Result |
|---|---|---|---|---|
| IO1 | Apple Health sync | Connect Apple Health → sync | Workouts, HR, sleep, weight in the app | PASS (simulator CI) |
| IO2 | Files import / backup | Import a FIT from Files; export/import a backup | Same as IM1 / BK1–BK3 | PASS (simulator CI) |
| IO3 | Scanner | Label photo and barcode photo | Same as IN7 / IN8 | PASS (simulator CI) |
