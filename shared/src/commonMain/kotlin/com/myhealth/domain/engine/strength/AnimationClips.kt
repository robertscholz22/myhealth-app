package com.myhealth.domain.engine.strength

import com.myhealth.domain.model.BodyFace

/**
 * The exercise animation clips (P18.1, re-authored in 0.7.1) — **pure data**, the way
 * `ExerciseCatalog` is: the poses of every shipped exercise written out as named-joint keyframes in
 * the DSL of `AnimationPoses.kt`, with nothing that knows about Compose, a canvas or a frame clock.
 * `ExerciseAnimations` maps all 87 catalog ids onto them; the renderer (P18.2) eases between the
 * keyframes and wraps back to the first.
 *
 * Every clip is authored in the normalised 100 x 220 box of the body model and stays inside it
 * (`an03`): a pose that lies down or throws the arms wide carries a `scale` below 1 for the *whole*
 * clip (never per keyframe — the figure must not pulse), while `dx` / `dy` vary per keyframe to
 * keep the contact point still. The offsets were computed by planting the contact — soles, hands,
 * forearms, the back of a lying figure — on one floor line, so a squat sinks between its feet, a
 * pull-up rises to its hands and a bridge lifts its hips off its shoulders. The renderer's
 * fit-to-content viewport (POLISH-21) frames whatever the clip leaves of the box.
 *
 * The strength clips live here; the stretches, flows and rolls live in `AnimationClipsMobility.kt`
 * (R10) and are re-exported at the bottom so every call site has one namespace.
 */
object AnimationClips {

    val SQUAT: AnimationClip = clip(
        "SQUAT", BodyFace.SIDE, LIFT,
        sidePose(
            trunk = 3f, pelvis = -3f, knee = 4f, ankle = -2f, shoulder = 35f, elbow = -150f,
            dx = 0.8f, dy = 15.5f, scale = 0.8f,
        ),
        sidePose(
            trunk = 35f, pelvis = -35f, hip = -88f, knee = 108f, ankle = -22f, shoulder = 35f,
            elbow = -150f, dx = -28.1f, dy = 52.7f, scale = 0.8f,
        ),
    )

    val FRONT_SQUAT: AnimationClip = clip(
        "FRONT_SQUAT", BodyFace.SIDE, LIFT,
        sidePose(
            trunk = 3f, pelvis = -3f, knee = 4f, ankle = -2f, shoulder = -85f, elbow = -125f,
            dx = 0.8f, dy = 15.2f, scale = 0.8f,
        ),
        sidePose(
            trunk = 18f, pelvis = -18f, hip = -90f, knee = 112f, ankle = -24f, shoulder = -95f,
            elbow = -125f, dx = -27.2f, dy = 54f, scale = 0.8f,
        ),
    )

    val GOBLET_SQUAT: AnimationClip = clip(
        "GOBLET_SQUAT", BodyFace.SIDE, LIFT,
        sidePose(
            trunk = 3f, pelvis = -3f, knee = 4f, ankle = -2f, shoulder = -30f, elbow = -125f,
            dx = 0.8f, dy = 14.6f, scale = 0.8f,
        ),
        sidePose(
            trunk = 22f, pelvis = -22f, hip = -95f, knee = 118f, ankle = -26f, shoulder = -40f,
            elbow = -125f, dx = -26.6f, dy = 56.9f, scale = 0.8f,
        ),
    )

    val WALL_SIT: AnimationClip = clip(
        "WALL_SIT", BodyFace.SIDE, HOLD,
        sidePose(
            hip = -90f, knee = 90f, shoulder = -8f, elbow = -10f, dx = -38f, dy = 58f,
            scale = 0.8f,
        ),
        sidePose(
            hip = -90f, knee = 90f, shoulder = -10f, elbow = -12f, dx = -38f, dy = 58f,
            scale = 0.8f,
        ),
    )

    val LEG_PRESS: AnimationClip = clip(
        "LEG_PRESS", BodyFace.SIDE, LIFT,
        sidePose(
            trunk = -50f, hip = -120f, knee = 125f, ankle = -20f, shoulder = 30f, elbow = -70f,
            dx = 4.6f, dy = 76.8f, scale = 0.62f,
        ),
        sidePose(
            trunk = -50f, hip = -112f, knee = 12f, ankle = -20f, shoulder = 30f, elbow = -70f,
            dx = 6.4f, dy = 76.8f, scale = 0.62f,
        ),
    )

    val HINGE: AnimationClip = clip(
        "HINGE", BodyFace.SIDE, LIFT,
        sidePose(knee = 3f, shoulder = -3f, dx = -28.9f, dy = 15.8f, scale = 0.85f),
        sidePose(
            trunk = 60f, pelvis = -60f, hip = -8f, knee = 12f, ankle = -3f, shoulder = -62f,
            elbow = -2f, dx = -33.8f, dy = 16.2f, scale = 0.85f,
        ),
    )

    val DEADLIFT: AnimationClip = clip(
        "DEADLIFT", BodyFace.SIDE, LIFT,
        sidePose(knee = 3f, shoulder = -3f, dx = -12.4f, dy = 15.8f, scale = 0.85f),
        sidePose(
            trunk = 55f, pelvis = -55f, hip = -40f, knee = 55f, ankle = -14f, shoulder = -60f,
            elbow = -2f, dx = -31.2f, dy = 26f, scale = 0.85f,
        ),
    )

    val SINGLE_LEG_HINGE: AnimationClip = clip(
        "SINGLE_LEG_HINGE", BodyFace.SIDE, LIFT,
        sidePose(knee = 3f, shoulder = -3f, dx = 0.2f, dy = 53.3f, scale = 0.5f),
        sidePose(
            trunk = 72f, pelvis = -72f, hip = -6f, knee = 12f, ankle = -4f, hipFar = 74f,
            kneeFar = -4f, ankleFar = 10f, shoulder = -72f, elbow = -2f, dx = -1.3f, dy = 53.5f,
            scale = 0.5f,
        ),
        mirror = true,
    )

    val LUNGE: AnimationClip = clip(
        "LUNGE", BodyFace.SIDE, LIFT,
        sidePose(
            trunk = 3f, pelvis = -3f, knee = 3f, shoulder = -6f, elbow = -10f, dx = 34f,
            dy = 19.4f, scale = 0.75f,
        ),
        sidePose(
            trunk = 6f, pelvis = -6f, hip = -82f, knee = 92f, ankle = -10f, hipFar = 28f,
            kneeFar = 50f, ankleFar = -20f, shoulder = -8f, elbow = -10f, dx = 3.1f, dy = 49.5f,
            scale = 0.75f,
        ),
        mirror = true,
    )

    val SPLIT_SQUAT: AnimationClip = clip(
        "SPLIT_SQUAT", BodyFace.SIDE, LIFT,
        sidePose(
            trunk = 8f, pelvis = -8f, hip = -30f, knee = 32f, ankle = -3f, hipFar = 32f,
            kneeFar = 95f, ankleFar = 20f, shoulder = -8f, elbow = -12f, dx = 14.7f, dy = 31.2f,
            scale = 0.75f,
        ),
        sidePose(
            trunk = 12f, pelvis = -12f, hip = -82f, knee = 92f, ankle = -12f, hipFar = 30f,
            kneeFar = 110f, ankleFar = 20f, shoulder = -10f, elbow = -12f, dx = 1.5f, dy = 56.7f,
            scale = 0.75f,
        ),
        mirror = true,
    )

    val STEP_UP: AnimationClip = clip(
        "STEP_UP", BodyFace.SIDE, LIFT,
        sidePose(
            trunk = 12f, pelvis = -12f, hip = -78f, knee = 88f, ankle = -10f, hipFar = 2f,
            kneeFar = 2f, shoulder = -10f, elbow = -12f, dx = -20.2f, dy = 31.9f, scale = 0.7f,
        ),
        sidePose(
            trunk = 4f, pelvis = -4f, hip = -2f, knee = 3f, hipFar = 12f, kneeFar = 24f,
            ankleFar = 20f, shoulder = -8f, elbow = -12f, dx = 6.3f, dy = 7.9f, scale = 0.7f,
        ),
        mirror = true,
    )

    val CALF_RAISE: AnimationClip = clip(
        "CALF_RAISE", BodyFace.SIDE, LIFT,
        sidePose(knee = 2f, shoulder = -4f, elbow = -6f, dx = -0.2f, dy = 10.4f, scale = 0.9f),
        sidePose(
            knee = 2f, ankle = 42f, shoulder = -4f, elbow = -6f, dx = -0.2f, dy = 7.4f,
            scale = 0.9f,
        ),
    )

    val JUMP: AnimationClip = clip(
        "JUMP", BodyFace.SIDE, FAST,
        sidePose(
            trunk = 32f, pelvis = -32f, hip = -75f, knee = 95f, ankle = -26f, shoulder = 30f,
            elbow = -12f, dx = -24.8f, dy = 64.4f, scale = 0.6f,
        ),
        sidePose(
            trunk = 2f, pelvis = -2f, hip = -12f, knee = 22f, ankle = 32f, shoulder = -145f,
            elbow = -6f, dx = -7.6f, dy = 13.6f, scale = 0.6f,
        ),
    )

    val SWING: AnimationClip = clip(
        "SWING", BodyFace.SIDE, FAST,
        sidePose(
            trunk = 48f, pelvis = -48f, hip = -8f, knee = 24f, ankle = -8f, shoulder = -28f,
            elbow = -4f, dx = -30.9f, dy = 17f, scale = 0.85f,
        ),
        sidePose(shoulder = -88f, elbow = -2f, dx = -33.9f, dy = 15.8f, scale = 0.85f),
    )

    val LEG_EXTENSION: AnimationClip = clip(
        "LEG_EXTENSION", BodyFace.SIDE, LIFT,
        sidePose(
            hip = -90f, knee = 92f, ankle = 10f, shoulder = 12f, elbow = -8f, dx = -14.3f,
            dy = 60.3f, scale = 0.75f,
        ),
        sidePose(
            hip = -90f, knee = 6f, ankle = 10f, shoulder = 12f, elbow = -8f, dx = -28.2f,
            dy = 60.3f, scale = 0.75f,
        ),
    )

    val LEG_CURL: AnimationClip = clip(
        "LEG_CURL", BodyFace.SIDE, LIFT,
        sidePose(
            trunk = 90f, hip = 2f, knee = 2f, ankle = 70f, shoulder = -55f, elbow = -130f,
            dx = 0.9f, dy = 90.7f, scale = 0.42f,
        ),
        sidePose(
            trunk = 90f, hip = 2f, knee = 118f, ankle = 70f, shoulder = -55f, elbow = -130f,
            dx = -8.1f, dy = 90.7f, scale = 0.42f,
        ),
    )

    val NORDIC: AnimationClip = clip(
        "NORDIC", BodyFace.SIDE, LIFT,
        sidePose(
            knee = 90f, shoulder = -20f, elbow = -120f, dx = -26.7f, dy = 74.6f, scale = 0.44f,
        ),
        sidePose(
            trunk = 48f, knee = 138f, shoulder = -100f, elbow = -5f, dx = -6.2f, dy = 85f,
            scale = 0.44f,
        ),
    )

    val HIP_ABDUCTION: AnimationClip = clip(
        "HIP_ABDUCTION", BodyFace.FRONT, LIFT,
        frontPose(shoulder = -10f, elbow = 6f, dy = 0.2f),
        frontPose(hipRight = -34f, shoulder = -10f, elbow = 6f, dx = -18.5f, dy = 0.2f),
        mirror = true,
    )

    val CARRY: AnimationClip = clip(
        "CARRY", BodyFace.SIDE, FLOW,
        sidePose(
            hip = -14f, hipFar = 12f, kneeFar = 8f, shoulder = -4f, elbow = -3f, dx = -0.1f,
            dy = 1.8f,
        ),
        sidePose(
            hip = 12f, knee = 8f, hipFar = -14f, kneeFar = 0f, shoulder = -4f, elbow = -3f,
            dx = -0.1f, dy = 1.3f,
        ),
        mirror = true,
    )

    val HIP_THRUST: AnimationClip = clip(
        "HIP_THRUST", BodyFace.SIDE, LIFT,
        sidePose(
            trunk = -90f, hip = -55f, knee = 100f, ankle = -45f, shoulder = 5f, elbow = -8f,
            dx = 5.7f, dy = 99.2f, scale = 0.5f,
        ),
        sidePose(
            trunk = -77.5f, torso = -26f, knee = 96f, ankle = -12f, shoulder = 5f, elbow = -8f,
            neck = 22f, head = 6f, dx = 7.4f, dy = 106.8f, scale = 0.5f,
        ),
    )
    // ---- re-exported from AnimationClipsUpper.kt / AnimationClipsMobility.kt (R10) ---------------

    val BENCH_PRESS: AnimationClip = UpperClips.BENCH_PRESS
    val PUSH_UP: AnimationClip = UpperClips.PUSH_UP
    val PLANK: AnimationClip = UpperClips.PLANK
    val OVERHEAD_PRESS: AnimationClip = UpperClips.OVERHEAD_PRESS
    val DIP: AnimationClip = UpperClips.DIP
    val ROW_BENT: AnimationClip = UpperClips.ROW_BENT
    val ROW_SEATED: AnimationClip = UpperClips.ROW_SEATED
    val INVERTED_ROW: AnimationClip = UpperClips.INVERTED_ROW
    val PULL_UP: AnimationClip = UpperClips.PULL_UP
    val PULLDOWN: AnimationClip = UpperClips.PULLDOWN
    val HANG: AnimationClip = UpperClips.HANG
    val LEG_RAISE: AnimationClip = UpperClips.LEG_RAISE
    val LATERAL_RAISE: AnimationClip = UpperClips.LATERAL_RAISE
    val REAR_DELT: AnimationClip = UpperClips.REAR_DELT
    val FACE_PULL: AnimationClip = UpperClips.FACE_PULL
    val CURL: AnimationClip = UpperClips.CURL
    val TRICEPS_PUSHDOWN: AnimationClip = UpperClips.TRICEPS_PUSHDOWN
    val SKULL_CRUSHER: AnimationClip = UpperClips.SKULL_CRUSHER
    val SIDE_PLANK: AnimationClip = UpperClips.SIDE_PLANK
    val DEAD_BUG: AnimationClip = UpperClips.DEAD_BUG
    val HOLLOW: AnimationClip = UpperClips.HOLLOW
    val TWIST: AnimationClip = UpperClips.TWIST
    val PALLOF: AnimationClip = UpperClips.PALLOF
    val BACK_EXTENSION: AnimationClip = UpperClips.BACK_EXTENSION
    val BIRD_DOG: AnimationClip = UpperClips.BIRD_DOG

    val DEEP_SQUAT: AnimationClip = MobilityClips.DEEP_SQUAT
    val COBRA: AnimationClip = MobilityClips.COBRA
    val COUCH_STRETCH: AnimationClip = MobilityClips.COUCH_STRETCH
    val PIGEON: AnimationClip = MobilityClips.PIGEON
    val HIP_SWITCH_90_90: AnimationClip = MobilityClips.HIP_SWITCH_90_90
    val FIGURE_FOUR: AnimationClip = MobilityClips.FIGURE_FOUR
    val STANDING_QUAD_STRETCH: AnimationClip = MobilityClips.STANDING_QUAD_STRETCH
    val WORLDS_GREATEST: AnimationClip = MobilityClips.WORLDS_GREATEST
    val LEG_SWINGS: AnimationClip = MobilityClips.LEG_SWINGS
    val HAMSTRING_STRETCH: AnimationClip = MobilityClips.HAMSTRING_STRETCH
    val CALF_STRETCH: AnimationClip = MobilityClips.CALF_STRETCH
    val ANKLE_ROCKS: AnimationClip = MobilityClips.ANKLE_ROCKS
    val CAT_COW: AnimationClip = MobilityClips.CAT_COW
    val ROCK_BACK: AnimationClip = MobilityClips.ROCK_BACK
    val CHILDS_POSE: AnimationClip = MobilityClips.CHILDS_POSE
    val THREAD_NEEDLE: AnimationClip = MobilityClips.THREAD_NEEDLE
    val DOWNWARD_DOG: AnimationClip = MobilityClips.DOWNWARD_DOG
    val OPEN_BOOK: AnimationClip = MobilityClips.OPEN_BOOK
    val SHOULDER_CARS: AnimationClip = MobilityClips.SHOULDER_CARS
    val WALL_SLIDE: AnimationClip = MobilityClips.WALL_SLIDE
    val DOORWAY_PEC: AnimationClip = MobilityClips.DOORWAY_PEC
    val LAT_STRETCH: AnimationClip = MobilityClips.LAT_STRETCH
    val TRICEPS_OVERHEAD_STRETCH: AnimationClip = MobilityClips.TRICEPS_OVERHEAD_STRETCH
    val BICEPS_WALL_STRETCH: AnimationClip = MobilityClips.BICEPS_WALL_STRETCH
    val WRIST_CIRCLES: AnimationClip = MobilityClips.WRIST_CIRCLES
    val NECK_TURN: AnimationClip = MobilityClips.NECK_TURN
    val FOAM_ROLL_PRONE: AnimationClip = MobilityClips.FOAM_ROLL_PRONE
    val FOAM_ROLL_SEATED: AnimationClip = MobilityClips.FOAM_ROLL_SEATED
    val FOAM_ROLL_SUPINE: AnimationClip = MobilityClips.FOAM_ROLL_SUPINE
    val FOAM_ROLL_SIDE: AnimationClip = MobilityClips.FOAM_ROLL_SIDE

    /** The fallback every unknown id lands on: the anatomical figure, not moving (`an09`). */
    val STANDING: AnimationClip = MobilityClips.STANDING

    /** Every clip, in declaration order — what `an02` / `an03` iterate. */
    val ALL: List<AnimationClip> = listOf(
        SQUAT, FRONT_SQUAT, GOBLET_SQUAT, DEEP_SQUAT, WALL_SIT, LEG_PRESS, HINGE, DEADLIFT,
        SINGLE_LEG_HINGE, LUNGE, SPLIT_SQUAT, STEP_UP, CALF_RAISE, JUMP, SWING, LEG_EXTENSION,
        LEG_CURL, NORDIC, HIP_ABDUCTION, CARRY, HIP_THRUST, BENCH_PRESS, PUSH_UP, PLANK,
        OVERHEAD_PRESS, DIP, ROW_BENT, ROW_SEATED, INVERTED_ROW, PULL_UP, PULLDOWN, HANG,
        LEG_RAISE, LATERAL_RAISE, REAR_DELT, FACE_PULL, CURL, TRICEPS_PUSHDOWN, SKULL_CRUSHER,
        SIDE_PLANK, DEAD_BUG, HOLLOW, TWIST, PALLOF, BACK_EXTENSION, COBRA, BIRD_DOG,
        COUCH_STRETCH, PIGEON, HIP_SWITCH_90_90, FIGURE_FOUR, STANDING_QUAD_STRETCH,
        WORLDS_GREATEST, LEG_SWINGS, HAMSTRING_STRETCH, CALF_STRETCH, ANKLE_ROCKS, CAT_COW,
        ROCK_BACK, CHILDS_POSE, THREAD_NEEDLE, DOWNWARD_DOG, OPEN_BOOK, SHOULDER_CARS, WALL_SLIDE,
        DOORWAY_PEC, LAT_STRETCH, TRICEPS_OVERHEAD_STRETCH, BICEPS_WALL_STRETCH, WRIST_CIRCLES,
        NECK_TURN, FOAM_ROLL_PRONE, FOAM_ROLL_SEATED, FOAM_ROLL_SUPINE, FOAM_ROLL_SIDE, STANDING,
    )

    init {
        check(ALL.map { it.id }.toSet().size == ALL.size) { "AnimationClips has duplicate ids" }
    }
}
