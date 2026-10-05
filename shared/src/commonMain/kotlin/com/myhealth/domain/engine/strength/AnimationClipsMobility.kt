package com.myhealth.domain.engine.strength

import com.myhealth.domain.model.BodyFace

/**
 * The mobility half of the clip set (P18.1, re-authored in 0.7.1): every stretch, the quadruped
 * flows, the joint circles and the four foam-roll positions, plus the static `STANDING` fallback.
 * Split out of `AnimationClips` for R10 only — the object above re-exports every value here, and
 * nothing else should name this one.
 *
 * A stretch is authored as *settle → deep*, so the wrap back to the first keyframe reads as the
 * breath out of the position rather than as a rep. `STRETCH` timing gives the deep keyframe a
 * 2.4 s hold; `SHOULDER_CARS` uses `CIRCLE` and relies on the shortest-arc angle interpolation of
 * `BodyPose.lerp` to close the circle through the wrap.
 */
internal object MobilityClips {

    val DEEP_SQUAT: AnimationClip = clip(
        "DEEP_SQUAT", BodyFace.SIDE, HOLD,
        sidePose(
            trunk = 22f, pelvis = -22f, hip = -112f, knee = 138f, ankle = -30f, shoulder = -40f,
            elbow = -110f, dx = -22.7f, dy = 74.6f, scale = 0.8f,
        ),
        sidePose(
            trunk = 24f, pelvis = -24f, hip = -114f, knee = 140f, ankle = -30f, shoulder = -42f,
            elbow = -110f, dx = -22.2f, dy = 75.8f, scale = 0.8f,
        ),
    )

    val COBRA: AnimationClip = clip(
        "COBRA", BodyFace.SIDE, STRETCH,
        sidePose(
            trunk = 90f, ankle = 70f, shoulder = -50f, elbow = -125f, dx = -5f, dy = 91.9f,
            scale = 0.39f,
        ),
        sidePose(
            trunk = 58f, hip = 32f, ankle = 70f, shoulder = -125f, elbow = -4f, neck = -8f,
            dx = -6.2f, dy = 99.8f, scale = 0.39f,
        ),
    )

    val COUCH_STRETCH: AnimationClip = clip(
        "COUCH_STRETCH", BodyFace.SIDE, STRETCH,
        sidePose(
            trunk = 6.5f, pelvis = -4f, hip = -88f, knee = 90f, hipFar = 22f, kneeFar = 150f,
            shoulder = -30f, elbow = -30f, dx = -18.7f, dy = 61.5f, scale = 0.7f,
        ),
        sidePose(
            trunk = -3.3f, pelvis = -2f, hip = -80f, knee = 78f, ankle = 10f, hipFar = 32f,
            kneeFar = 150f, shoulder = -30f, elbow = -30f, dx = -25.4f, dy = 61.7f, scale = 0.7f,
        ),
        mirror = true,
    )

    val PIGEON: AnimationClip = clip(
        "PIGEON", BodyFace.SIDE, STRETCH,
        sidePose(
            trunk = 6f, pelvis = -6f, hip = -95f, knee = 150f, ankle = 20f, hipFar = 88f,
            kneeFar = -4f, ankleFar = 40f, shoulder = -60f, elbow = -20f, dx = 2f, dy = 90.4f,
            scale = 0.41f,
        ),
        sidePose(
            trunk = 50f, pelvis = -50f, hip = -95f, knee = 150f, ankle = 20f, hipFar = 88f,
            kneeFar = -4f, ankleFar = 40f, shoulder = -120f, elbow = -10f, dx = -8.5f, dy = 90.4f,
            scale = 0.41f,
        ),
        mirror = true,
    )

    val HIP_SWITCH_90_90: AnimationClip = clip(
        "HIP_SWITCH_90_90", BodyFace.FRONT, STRETCH,
        frontPose(
            hip = 50f, knee = 40f, hipRight = 88f, kneeRight = -95f, shoulder = -20f, elbow = 5f,
            dx = 19.5f, dy = 53.9f, scale = 0.6f,
        ),
        frontPose(
            hip = -88f, knee = 95f, hipRight = -50f, kneeRight = -40f, shoulder = -20f, elbow = 5f,
            dx = -19.5f, dy = 53.9f, scale = 0.6f,
        ),
    )

    val FIGURE_FOUR: AnimationClip = clip(
        "FIGURE_FOUR", BodyFace.SIDE, STRETCH,
        sidePose(
            trunk = -90f, hip = -80f, knee = 100f, hipFar = -95f, kneeFar = 95f, shoulder = -60f,
            elbow = -60f, dx = 8.5f, dy = 100.7f, scale = 0.45f,
        ),
        sidePose(
            trunk = -90f, hip = -100f, knee = 100f, hipFar = -115f, kneeFar = 95f, shoulder = -75f,
            elbow = -60f, dx = 12f, dy = 100.7f, scale = 0.45f,
        ),
        mirror = true,
    )

    val STANDING_QUAD_STRETCH: AnimationClip = clip(
        "STANDING_QUAD_STRETCH", BodyFace.SIDE, STRETCH,
        sidePose(
            hip = 8f, knee = 128f, ankle = 20f, shoulder = 40f, elbow = -70f, shoulderFar = -60f,
            elbowFar = -10f, dx = -11.8f, dy = 51f, scale = 0.79f,
        ),
        sidePose(
            hip = 14f, knee = 138f, ankle = 20f, shoulder = 44f, elbow = -74f, shoulderFar = -60f,
            elbowFar = -10f, dx = -14.4f, dy = 55.3f, scale = 0.79f,
        ),
        mirror = true,
    )

    val WORLDS_GREATEST: AnimationClip = clip(
        "WORLDS_GREATEST", BodyFace.SIDE, STRETCH,
        sidePose(
            trunk = 55f, pelvis = -55f, hip = -100f, knee = 100f, ankle = -10f, hipFar = 58f,
            kneeFar = 4f, ankleFar = -30f, shoulder = -60f, elbow = -2f, dx = -12.8f, dy = 80.3f,
            scale = 0.42f,
        ),
        sidePose(
            trunk = 55f, pelvis = -55f, hip = -100f, knee = 100f, ankle = -10f, hipFar = 58f,
            kneeFar = 4f, ankleFar = -30f, shoulder = -235f, elbow = -2f, shoulderFar = -60f,
            dx = -12.8f, dy = 80.3f, scale = 0.42f,
        ),
        mirror = true,
    )

    val LEG_SWINGS: AnimationClip = clip(
        "LEG_SWINGS", BodyFace.SIDE, FLOW,
        sidePose(
            hip = -62f, knee = 8f, ankle = 10f, hipFar = 0f, kneeFar = 2f, ankleFar = 0f,
            shoulder = -80f, elbow = -2f, dx = -9.4f, dy = 36.2f, scale = 0.66f,
        ),
        sidePose(
            hip = 30f, knee = 22f, ankle = 20f, hipFar = 0f, kneeFar = 2f, ankleFar = 0f,
            shoulder = -80f, elbow = -2f, dx = -9.4f, dy = 36.2f, scale = 0.66f,
        ),
        mirror = true,
    )

    val HAMSTRING_STRETCH: AnimationClip = clip(
        "HAMSTRING_STRETCH", BodyFace.SIDE, STRETCH,
        sidePose(
            trunk = 42f, pelvis = -42f, hip = -46f, knee = 4f, ankle = -10f, hipFar = -2f,
            kneeFar = 8f, ankleFar = -4f, shoulder = -50f, elbow = -8f, dx = -30.8f, dy = 16f,
            scale = 0.85f,
        ),
        sidePose(
            trunk = 52f, pelvis = -52f, hip = -50f, knee = 2f, ankle = -10f, hipFar = -2f,
            kneeFar = 8f, ankleFar = -4f, shoulder = -60f, elbow = -8f, dx = -30.8f, dy = 16f,
            scale = 0.85f,
        ),
        mirror = true,
    )

    val CALF_STRETCH: AnimationClip = clip(
        "CALF_STRETCH", BodyFace.SIDE, STRETCH,
        sidePose(
            trunk = 10f, pelvis = -10f, hip = -36f, knee = 40f, ankle = -14f, hipFar = 16f,
            kneeFar = 2f, ankleFar = -26f, shoulder = -95f, elbow = -4f, dx = -17.2f, dy = 38.5f,
            scale = 0.66f,
        ),
        sidePose(
            trunk = 12f, pelvis = -12f, hip = -42f, knee = 46f, ankle = -16f, hipFar = 20f,
            kneeFar = 2f, ankleFar = -32f, shoulder = -100f, elbow = -4f, dx = -19.6f, dy = 39.7f,
            scale = 0.66f,
        ),
        mirror = true,
    )

    val ANKLE_ROCKS: AnimationClip = clip(
        "ANKLE_ROCKS", BodyFace.SIDE, STRETCH,
        sidePose(
            trunk = 9.7f, pelvis = -6f, hip = -90f, knee = 90f, hipFar = 0f, ankleFar = 10f,
            shoulder = -40f, elbow = -40f, dx = -0.7f, dy = 66.4f, scale = 0.63f,
        ),
        sidePose(
            trunk = 18.2f, pelvis = -8f, hip = -96f, knee = 66f, ankle = 30f, hipFar = 4f,
            kneeFar = 90f, ankleFar = 10f, shoulder = -50f, elbow = -40f, dx = -8.7f, dy = 67.2f,
            scale = 0.63f,
        ),
        mirror = true,
    )

    val CAT_COW: AnimationClip = clip(
        "CAT_COW", BodyFace.SIDE, FLOW,
        sidePose(
            trunk = 88f, pelvis = -2f, hip = -86f, knee = 86f, ankle = 14f, shoulder = -88f,
            elbow = -2f, dx = -7.1f, dy = 64f, scale = 0.5f,
        ),
        sidePose(
            trunk = 88f, pelvis = 14f, hip = -100f, knee = 86f, ankle = 14f, shoulder = -88f,
            elbow = -2f, neck = 18f, head = 8f, dx = -7.1f, dy = 64f, scale = 0.5f,
        ),
        sidePose(
            trunk = 88f, pelvis = -18f, hip = -70f, knee = 86f, ankle = 14f, shoulder = -88f,
            elbow = -2f, neck = -16f, head = -8f, dx = -7.1f, dy = 64f, scale = 0.5f,
        ),
    )

    val ROCK_BACK: AnimationClip = clip(
        "ROCK_BACK", BodyFace.SIDE, FLOW,
        sidePose(
            trunk = 88f, pelvis = -2f, hip = -86f, knee = 86f, ankle = 14f, shoulder = -88f,
            elbow = -2f, dx = 3.4f, dy = 69.5f, scale = 0.42f,
        ),
        sidePose(
            trunk = 96f, pelvis = 6f, hip = -100f, knee = 92f, ankle = 14f, shoulder = -150f,
            elbow = -2f, dx = -21.1f, dy = 80.7f, scale = 0.42f,
        ),
    )

    val CHILDS_POSE: AnimationClip = clip(
        "CHILDS_POSE", BodyFace.SIDE, STRETCH,
        sidePose(
            trunk = 96f, pelvis = 6f, hip = -100f, knee = 92f, ankle = 14f, shoulder = -150f,
            elbow = -2f, dx = -18.1f, dy = 79.2f, scale = 0.47f,
        ),
        sidePose(
            trunk = 110f, pelvis = 10f, hip = -125f, knee = 100f, ankle = 14f, shoulder = -170f,
            elbow = -2f, neck = 6f, dx = -18.1f, dy = 75f, scale = 0.47f,
        ),
    )

    val THREAD_NEEDLE: AnimationClip = clip(
        "THREAD_NEEDLE", BodyFace.SIDE, STRETCH,
        sidePose(
            trunk = 88f, pelvis = -2f, hip = -86f, knee = 86f, ankle = 14f, shoulder = -88f,
            elbow = -2f, dx = -7f, dy = 66f, scale = 0.5f,
        ),
        sidePose(
            trunk = 94f, pelvis = -2f, hip = -86f, knee = 86f, ankle = 14f, shoulder = -40f,
            elbow = -5f, shoulderFar = -88f, elbowFar = -2f, neck = 24f, dx = -3.3f, dy = 62.6f,
            scale = 0.5f,
        ),
        mirror = true,
    )

    val DOWNWARD_DOG: AnimationClip = clip(
        "DOWNWARD_DOG", BodyFace.SIDE, STRETCH,
        sidePose(
            trunk = 115.1f, hip = -84f, knee = 4f, ankle = -40f, shoulder = -176f, elbow = -2f,
            neck = 4f, dx = -17.3f, dy = 72.9f, scale = 0.47f,
        ),
        sidePose(
            trunk = 117.7f, hip = -92f, knee = 2f, ankle = -48f, shoulder = -178f, elbow = -2f,
            neck = 4f, dx = -16.4f, dy = 71.2f, scale = 0.47f,
        ),
    )

    val OPEN_BOOK: AnimationClip = clip(
        "OPEN_BOOK", BodyFace.FRONT, STRETCH,
        frontPose(
            trunk = -90f, hip = -10f, knee = 25f, shoulder = -160f, elbow = 60f,
            shoulderRight = -80f, elbowRight = 4f, dx = 1.8f, dy = 98f, scale = 0.37f,
        ),
        frontPose(
            trunk = -90f, hip = -10f, knee = 25f, shoulder = -160f, elbow = 60f,
            shoulderRight = -175f, elbowRight = 4f, neck = -6f, head = -18f, dx = 9.5f, dy = 98f,
            scale = 0.37f,
        ),
        mirror = true,
    )

    val SHOULDER_CARS: AnimationClip = clip(
        "SHOULDER_CARS", BodyFace.SIDE, CIRCLE,
        sidePose(elbow = -2f, dx = -0.8f, dy = 46.9f, scale = 0.56f),
        sidePose(
            shoulder = -120f, elbow = -2f, shoulderFar = 0f, dx = -0.8f, dy = 46.9f, scale = 0.56f,
        ),
        sidePose(
            shoulder = -240f, elbow = -2f, shoulderFar = 0f, dx = -0.8f, dy = 46.9f, scale = 0.56f,
        ),
        mirror = true,
    )

    val WALL_SLIDE: AnimationClip = clip(
        "WALL_SLIDE", BodyFace.FRONT, FLOW,
        frontPose(shoulder = 74f, elbow = 106f, dy = 32.3f, scale = 0.7f),
        frontPose(shoulder = 150f, elbow = 20f, dy = 32.3f, scale = 0.7f),
    )

    val DOORWAY_PEC: AnimationClip = clip(
        "DOORWAY_PEC", BodyFace.SIDE, STRETCH,
        sidePose(
            trunk = 6f, pelvis = -6f, hip = -20f, knee = 20f, hipFar = 14f, shoulder = 80f,
            elbow = 95f, dx = 9.3f, dy = 28.6f, scale = 0.75f,
        ),
        sidePose(
            trunk = 12f, pelvis = -12f, hip = -26f, knee = 26f, hipFar = 16f, shoulder = 92f,
            elbow = 95f, dx = 5.9f, dy = 30f, scale = 0.75f,
        ),
    )

    val LAT_STRETCH: AnimationClip = clip(
        "LAT_STRETCH", BodyFace.SIDE, STRETCH,
        sidePose(
            trunk = 45f, pelvis = -45f, hip = -10f, knee = 10f, shoulder = -178f, elbow = -2f,
            dx = -37.3f, dy = 37.7f, scale = 0.65f,
        ),
        sidePose(
            trunk = 58f, pelvis = -58f, hip = -12f, knee = 12f, shoulder = -180f, elbow = -2f,
            dx = -38.3f, dy = 37.9f, scale = 0.65f,
        ),
    )

    val TRICEPS_OVERHEAD_STRETCH: AnimationClip = clip(
        "TRICEPS_OVERHEAD_STRETCH", BodyFace.SIDE, STRETCH,
        sidePose(
            shoulder = -160f, elbow = -120f, shoulderFar = -150f, elbowFar = -130f, dx = -1f,
            dy = 31.9f, scale = 0.7f,
        ),
        sidePose(
            shoulder = -168f, elbow = -135f, shoulderFar = -158f, elbowFar = -132f, dx = -1f,
            dy = 31.9f, scale = 0.7f,
        ),
        mirror = true,
    )

    val BICEPS_WALL_STRETCH: AnimationClip = clip(
        "BICEPS_WALL_STRETCH", BodyFace.SIDE, STRETCH,
        sidePose(
            trunk = 4f, pelvis = -4f, hip = -12f, knee = 12f, hipFar = 10f, shoulder = 88f,
            elbow = 2f, shoulderFar = -4f, elbowFar = -6f, dx = 23.1f, dy = 22f, scale = 0.8f,
        ),
        sidePose(
            trunk = 8f, pelvis = -8f, hip = -16f, knee = 16f, hipFar = 12f, shoulder = 100f,
            elbow = 2f, shoulderFar = -4f, elbowFar = -6f, dx = 20.6f, dy = 22.6f, scale = 0.8f,
        ),
        mirror = true,
    )

    val WRIST_CIRCLES: AnimationClip = clip(
        "WRIST_CIRCLES", BodyFace.FRONT, FLOW,
        frontPose(shoulder = -8f, elbow = -95f, wrist = -45f, dy = 10.9f, scale = 0.9f),
        frontPose(shoulder = -8f, elbow = -95f, dy = 10.9f, scale = 0.9f),
        frontPose(shoulder = -8f, elbow = -95f, wrist = 45f, dy = 10.9f, scale = 0.9f),
    )

    val NECK_TURN: AnimationClip = clip(
        "NECK_TURN", BodyFace.FRONT, FLOW,
        frontPose(shoulder = -6f, elbow = 4f, neck = -8f, head = -16f, dy = 5.6f, scale = 0.95f),
        frontPose(shoulder = -6f, elbow = 4f, neck = 8f, head = 16f, dy = 5.6f, scale = 0.95f),
    )

    val FOAM_ROLL_PRONE: AnimationClip = clip(
        "FOAM_ROLL_PRONE", BodyFace.SIDE, FLOW,
        sidePose(
            trunk = 84f, hip = 10f, knee = 30f, ankle = 20f, shoulder = -95f, elbow = -95f,
            dx = -3.7f, dy = 90.9f, scale = 0.42f,
        ),
        sidePose(
            trunk = 84f, hip = 10f, knee = 30f, ankle = 20f, shoulder = -75f, elbow = -110f,
            dx = 1.8f, dy = 90.9f, scale = 0.42f,
        ),
    )

    val FOAM_ROLL_SEATED: AnimationClip = clip(
        "FOAM_ROLL_SEATED", BodyFace.SIDE, FLOW,
        sidePose(
            trunk = -12f, pelvis = 12f, hip = -95f, knee = 6f, ankle = 10f, shoulder = 62f,
            elbow = -10f, dx = -3.5f, dy = 92.7f, scale = 0.5f,
        ),
        sidePose(
            trunk = -22f, pelvis = 22f, hip = -95f, knee = 6f, ankle = 10f, shoulder = 80f,
            elbow = -10f, dx = 1.3f, dy = 95.5f, scale = 0.5f,
        ),
    )

    val FOAM_ROLL_SUPINE: AnimationClip = clip(
        "FOAM_ROLL_SUPINE", BodyFace.SIDE, FLOW,
        sidePose(
            trunk = -96f, pelvis = 6f, hip = -60f, knee = 100f, ankle = -45f, shoulder = -150f,
            elbow = -140f, dx = 8.8f, dy = 88.5f, scale = 0.5f,
        ),
        sidePose(
            trunk = -84f, pelvis = -6f, hip = -60f, knee = 100f, ankle = -45f, shoulder = -150f,
            elbow = -140f, dx = 7.9f, dy = 97.8f, scale = 0.5f,
        ),
    )

    val FOAM_ROLL_SIDE: AnimationClip = clip(
        "FOAM_ROLL_SIDE", BodyFace.FRONT, FLOW,
        frontPose(
            trunk = -90f, hip = -6f, knee = 10f, shoulder = -172f, elbow = -4f,
            shoulderRight = -30f, elbowRight = 40f, dx = 8.7f, dy = 98.7f, scale = 0.36f,
        ),
        frontPose(
            trunk = -90f, hip = -6f, knee = 10f, shoulder = -172f, elbow = -4f,
            shoulderRight = -30f, elbowRight = 40f, dx = 10f, dy = 98.7f, scale = 0.36f,
        ),
        mirror = true,
    )

    /**
     * The anatomical figure, not moving: two *identical* keyframes, so it has the same "2 to 4
     * keyframes" shape as every other clip (`an02`) and still renders as a still (`an09`).
     */
    val STANDING: AnimationClip = clip(
        "STANDING", BodyFace.FRONT, HOLD,
        frontPose(shoulder = -6f, elbow = 4f, dy = 0.2f),
        frontPose(shoulder = -6f, elbow = 4f, dy = 0.2f),
    )
}
