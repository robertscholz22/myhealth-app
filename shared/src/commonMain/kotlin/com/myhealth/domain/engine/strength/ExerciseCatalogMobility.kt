package com.myhealth.domain.engine.strength

import com.myhealth.domain.model.Equipment as Eq
import com.myhealth.domain.model.Exercise
import com.myhealth.domain.model.MovementPattern as P
import com.myhealth.domain.model.MuscleGroup as M

/**
 * The mobility half of the catalog (PLAN §P17): stretches, joint rotations and foam-roll drills.
 *
 * Every entry here is `pattern = MOBILITY` and `isTimed = true` — a mobility drill is always
 * prescribed as a hold in seconds, never as a rep count — and its **[Exercise.primary] is the
 * group being mobilised** (stretched, rolled or rotated) rather than the one doing work, with
 * [Exercise.secondary] for what assists or is stretched along with it. That reading is what makes
 * the body figure highlight the right silhouette for "pigeon" without a second model, and every
 * one of the sixteen [M] members is somebody's mobility primary (`mob02`).
 *
 * Mobility deposits **no** muscle load (`MuscleDistribution.isMobilityOnly`) and carries no weight
 * (`ProgressionDefaults.carriesLoad`): it is recovery, and the P16 progression only grows the hold.
 *
 * Ids are permanent like every other catalog id and are prefixed `MOB_` so a stored row says at a
 * glance which half of the catalog it came from.
 */
internal val MOBILITY_EXERCISES: List<Exercise> = listOf(
    // ---- hips, knees and ankles ----------------------------------------------------------------
    mob(
        "COUCH_STRETCH", "Couch stretch",
        primary = setOf(M.QUADS), secondary = setOf(M.ABS, M.GLUTES), unilateral = true,
        cue = "Back knee against the wall, squeeze the back glute — never arch the lower back.",
    ),
    mob(
        "PIGEON", "Pigeon stretch",
        primary = setOf(M.GLUTES), secondary = setOf(M.ADDUCTORS, M.LOWER_BACK), unilateral = true,
        cue = "Front shin across, hips square to the floor; breathe out and sink, do not force it.",
    ),
    mob(
        "HIP_SWITCH_90_90", "90/90 hip switch",
        primary = setOf(M.GLUTES, M.ADDUCTORS), secondary = setOf(M.LOWER_BACK),
        cue = "Chest tall, switch the knees side to side slowly — the feet stay on the floor.",
    ),
    mob(
        "STANDING_HAMSTRING_STRETCH", "Standing hamstring stretch",
        primary = setOf(M.HAMSTRINGS), secondary = setOf(M.CALVES, M.LOWER_BACK), unilateral = true,
        cue = "Heel on a low step, hinge from the hip with a flat back — not a rounded reach.",
    ),
    mob(
        "WALL_CALF_STRETCH", "Wall calf stretch",
        primary = setOf(M.CALVES), secondary = setOf(M.HAMSTRINGS), unilateral = true,
        cue = "Back heel down, back knee straight; bend it a little to reach the lower calf.",
    ),
    mob(
        "ANKLE_ROCKS", "Ankle rocks",
        primary = setOf(M.CALVES), secondary = setOf(M.QUADS), unilateral = true,
        cue = "Knee travels over the little toe, heel glued down — the runner's ankle drill.",
    ),
    mob(
        "DEEP_SQUAT_HOLD", "Deep squat hold",
        primary = setOf(M.ADDUCTORS, M.GLUTES), secondary = setOf(M.QUADS, M.CALVES),
        cue = "Sit all the way down, elbows inside the knees, chest up; hold and breathe.",
    ),
    mob(
        "ADDUCTOR_ROCK_BACK", "Adductor rock-back",
        primary = setOf(M.ADDUCTORS), secondary = setOf(M.GLUTES, M.LOWER_BACK),
        cue = "On all fours, one leg out to the side, rock the hips back slowly to the heel.",
    ),
    mob(
        "FIGURE_FOUR_GLUTE", "Figure-four glute stretch",
        primary = setOf(M.GLUTES), secondary = setOf(M.LOWER_BACK), unilateral = true,
        cue = "Ankle over the opposite knee, pull the far thigh in; keep the head on the floor.",
    ),
    mob(
        "STANDING_QUAD_STRETCH", "Standing quad stretch",
        primary = setOf(M.QUADS), secondary = setOf(M.ABS), unilateral = true,
        cue = "Knees together, push the hip forward — the stretch comes from the hip, not the pull.",
    ),
    mob(
        "WORLDS_GREATEST_STRETCH", "World's greatest stretch",
        primary = setOf(M.ADDUCTORS, M.QUADS),
        secondary = setOf(M.GLUTES, M.OBLIQUES, M.SHOULDERS_FRONT), unilateral = true,
        cue = "Deep lunge, elbow to the instep, then open the chest to the ceiling and hold.",
    ),
    mob(
        "LEG_SWINGS", "Leg swings",
        primary = setOf(M.HAMSTRINGS), secondary = setOf(M.GLUTES, M.QUADS, M.ADDUCTORS),
        unilateral = true,
        cue = "Hold a wall, swing relaxed and let the range grow — no forcing at the end.",
    ),

    // ---- spine and trunk -----------------------------------------------------------------------
    mob(
        "CAT_COW", "Cat-cow",
        primary = setOf(M.LOWER_BACK), secondary = setOf(M.ABS, M.TRAPS),
        cue = "Move one vertebra at a time, in time with the breath; no speed at all.",
    ),
    mob(
        "THREAD_THE_NEEDLE", "Thread the needle",
        primary = setOf(M.TRAPS), secondary = setOf(M.SHOULDERS_REAR, M.LATS, M.OBLIQUES),
        unilateral = true,
        cue = "Slide the arm under the body and let the upper back — not the neck — do the turn.",
    ),
    mob(
        "THORACIC_ROTATION", "Open-book thoracic rotation",
        primary = setOf(M.OBLIQUES), secondary = setOf(M.CHEST, M.LOWER_BACK), unilateral = true,
        cue = "Side-lying, knees stacked and still; open the top arm and follow it with the eyes.",
    ),
    mob(
        "CHILDS_POSE", "Child's pose",
        primary = setOf(M.LATS), secondary = setOf(M.LOWER_BACK, M.SHOULDERS_FRONT),
        cue = "Hips to the heels, arms long, ribs soft — the reset between the harder holds.",
    ),
    mob(
        "DOWNWARD_DOG", "Downward dog",
        primary = setOf(M.CALVES, M.HAMSTRINGS), secondary = setOf(M.LATS, M.SHOULDERS_FRONT),
        cue = "Push the floor away, long spine; pedal the heels rather than forcing them down.",
    ),
    mob(
        "COBRA", "Cobra",
        primary = setOf(M.ABS), secondary = setOf(M.CHEST, M.LOWER_BACK),
        cue = "Hips stay down, elbows soft, shoulders away from the ears — no cranking the back.",
    ),

    // ---- shoulders, arms and neck --------------------------------------------------------------
    mob(
        "SHOULDER_CARS", "Shoulder CARs",
        primary = setOf(M.SHOULDERS_FRONT), secondary = setOf(M.SHOULDERS_REAR, M.TRAPS),
        unilateral = true,
        cue = "One slow circle to the outside of the range; the ribs and hips do not follow.",
    ),
    mob(
        "WALL_SLIDES", "Wall slides",
        primary = setOf(M.SHOULDERS_REAR), secondary = setOf(M.TRAPS, M.SHOULDERS_FRONT),
        cue = "Wrists and lower back on the wall, slide up only as far as they both stay there.",
    ),
    mob(
        "DOORWAY_PEC_STRETCH", "Doorway pec stretch",
        primary = setOf(M.CHEST), secondary = setOf(M.SHOULDERS_FRONT, M.BICEPS), unilateral = true,
        cue = "Forearm on the frame at shoulder height, turn away from the arm — do not lean.",
    ),
    mob(
        "BAND_PULL_APART_SLOW", "Band pull-apart (slow)",
        primary = setOf(M.SHOULDERS_REAR), secondary = setOf(M.TRAPS), equipment = Eq.BAND,
        cue = "Light band, arms straight, three seconds out and three back — this is not a set.",
    ),
    mob(
        "DOORWAY_LAT_STRETCH", "Doorway lat stretch",
        primary = setOf(M.LATS), secondary = setOf(M.TRICEPS, M.SHOULDERS_FRONT), unilateral = true,
        cue = "Hold the frame, sit the hips back and away; feel it down the side of the ribs.",
    ),
    mob(
        "OVERHEAD_TRICEPS_STRETCH", "Overhead triceps stretch",
        primary = setOf(M.TRICEPS), secondary = setOf(M.LATS, M.SHOULDERS_FRONT), unilateral = true,
        cue = "Elbow to the ceiling, hand down the spine, gentle pressure from the other hand.",
    ),
    mob(
        "WALL_BICEPS_STRETCH", "Wall biceps stretch",
        primary = setOf(M.BICEPS), secondary = setOf(M.CHEST, M.SHOULDERS_FRONT), unilateral = true,
        cue = "Palm flat on the wall behind you, thumb up, then turn the chest slowly away.",
    ),
    mob(
        "WRIST_CIRCLES", "Wrist circles",
        primary = setOf(M.FOREARMS), secondary = setOf(M.BICEPS),
        cue = "Fingers laced, slow circles both ways, then flex and extend against the floor.",
    ),
    mob(
        "NECK_ROTATIONS", "Neck rotations",
        primary = setOf(M.TRAPS), secondary = setOf(M.SHOULDERS_REAR),
        cue = "Small, slow half-circles front only; the shoulders stay down and quiet.",
    ),

    // ---- foam roller ---------------------------------------------------------------------------
    mob(
        "FOAM_ROLL_QUADS", "Foam-roll quads",
        primary = setOf(M.QUADS), secondary = setOf(M.ABS),
        equipment = Eq.FOAM_ROLLER, unilateral = true,
        cue = "Roll hip to knee slowly and pause on the sore spot until it lets go.",
    ),
    mob(
        "FOAM_ROLL_HAMSTRINGS", "Foam-roll hamstrings",
        primary = setOf(M.HAMSTRINGS), secondary = setOf(M.GLUTES),
        equipment = Eq.FOAM_ROLLER, unilateral = true,
        cue = "Sit on the roller, cross the other ankle over to load it, roll sit-bone to knee.",
    ),
    mob(
        "FOAM_ROLL_CALVES", "Foam-roll calves",
        primary = setOf(M.CALVES), secondary = setOf(M.HAMSTRINGS),
        equipment = Eq.FOAM_ROLLER, unilateral = true,
        cue = "Ankle to knee, turn the foot in and out to reach both heads of the calf.",
    ),
    mob(
        "FOAM_ROLL_THORACIC", "Foam-roll thoracic spine",
        primary = setOf(M.TRAPS), secondary = setOf(M.SHOULDERS_REAR, M.LATS),
        equipment = Eq.FOAM_ROLLER,
        cue = "Roller across the upper back, hands behind the head, extend over it — not the ribs.",
    ),
    mob(
        "FOAM_ROLL_LATS", "Foam-roll lats",
        primary = setOf(M.LATS), secondary = setOf(M.TRICEPS, M.OBLIQUES),
        equipment = Eq.FOAM_ROLLER, unilateral = true,
        cue = "Side-lying, arm long overhead, roll the armpit to the bottom rib.",
    ),
    mob(
        "FOAM_ROLL_GLUTES", "Foam-roll glutes",
        primary = setOf(M.GLUTES), secondary = setOf(M.LOWER_BACK),
        equipment = Eq.FOAM_ROLLER, unilateral = true,
        cue = "Sit on the roller, ankle over the knee, hunt the tender spot and stay there.",
    ),
)

/**
 * One mobility entry. The shared fields ([P.MOBILITY], `isTimed`, the `MOB_` prefix) are set here
 * exactly once, which is what keeps the list above readable and the file inside R10.
 */
private fun mob(
    id: String,
    name: String,
    primary: Set<M>,
    secondary: Set<M> = emptySet(),
    equipment: Eq = Eq.BODYWEIGHT,
    unilateral: Boolean = false,
    cue: String,
): Exercise = Exercise(
    id = "MOB_$id",
    name = name,
    primary = primary,
    secondary = secondary,
    equipment = equipment,
    pattern = P.MOBILITY,
    unilateral = unilateral,
    isTimed = true,
    cue = cue,
)
