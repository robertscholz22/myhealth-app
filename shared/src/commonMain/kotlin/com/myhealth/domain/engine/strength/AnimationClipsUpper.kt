package com.myhealth.domain.engine.strength

import com.myhealth.domain.model.BodyFace

/**
 * The upper-body and trunk clips (P18.1, re-authored in 0.7.1): presses, rows, pulls, the hangs,
 * the arm isolations and the core holds. Split out of `AnimationClips` for R10 only — that object
 * re-exports every value here, and nothing else should name this one. Same conventions as the
 * lower-body file: one scale per clip, contacts planted on one floor line.
 */
internal object UpperClips {

    val BENCH_PRESS: AnimationClip = clip(
        "BENCH_PRESS", BodyFace.SIDE, LIFT,
        sidePose(
            trunk = -90f, hip = -60f, knee = 100f, ankle = -45f, shoulder = -90f, elbow = -2f,
            dx = 7.6f, dy = 96.2f, scale = 0.5f,
        ),
        sidePose(
            trunk = -90f, hip = -60f, knee = 100f, ankle = -45f, shoulder = 25f, elbow = -118f,
            dx = 7.6f, dy = 96.2f, scale = 0.5f,
        ),
    )

    val PUSH_UP: AnimationClip = clip(
        "PUSH_UP", BodyFace.SIDE, LIFT,
        sidePose(
            trunk = 66f, hip = 4f, shoulder = -95f, elbow = -2f, dx = -1f, dy = 85.1f,
            scale = 0.45f,
        ),
        sidePose(
            trunk = 65.4f, hip = 4f, shoulder = -40f, elbow = -55f, dx = -1.2f, dy = 84.6f,
            scale = 0.45f,
        ),
    )

    val PLANK: AnimationClip = clip(
        "PLANK", BodyFace.SIDE, HOLD,
        sidePose(
            trunk = 77.2f, hip = 4f, shoulder = -90f, elbow = -92f, dx = -1.6f, dy = 94.5f,
            scale = 0.42f,
        ),
        sidePose(
            trunk = 76.2f, hip = 6f, shoulder = -90f, elbow = -92f, dx = -1.5f, dy = 95f,
            scale = 0.42f,
        ),
    )

    val OVERHEAD_PRESS: AnimationClip = clip(
        "OVERHEAD_PRESS", BodyFace.FRONT, LIFT,
        frontPose(shoulder = 35f, elbow = 145f, dy = 32.3f, scale = 0.7f),
        frontPose(shoulder = 172f, elbow = -8f, dy = 32.3f, scale = 0.7f),
    )

    val DIP: AnimationClip = clip(
        "DIP", BodyFace.SIDE, LIFT,
        sidePose(
            trunk = 8f, hip = -10f, knee = 60f, ankle = 20f, shoulder = 12f, elbow = -4f,
            dx = 7.3f, dy = 22.6f, scale = 0.75f,
        ),
        sidePose(
            trunk = 12f, hip = -10f, knee = 60f, ankle = 20f, shoulder = 62f, elbow = -80f,
            dx = 12.4f, dy = 40.7f, scale = 0.75f,
        ),
    )

    val ROW_BENT: AnimationClip = clip(
        "ROW_BENT", BodyFace.SIDE, LIFT,
        sidePose(
            trunk = 48f, pelvis = -48f, hip = -8f, knee = 22f, ankle = -8f, shoulder = -48f,
            elbow = -4f, dx = -28.8f, dy = 16.8f, scale = 0.85f,
        ),
        sidePose(
            trunk = 48f, pelvis = -48f, hip = -8f, knee = 22f, ankle = -8f, shoulder = 28f,
            elbow = -80f, dx = -28.8f, dy = 16.8f, scale = 0.85f,
        ),
    )

    val ROW_SEATED: AnimationClip = clip(
        "ROW_SEATED", BodyFace.SIDE, LIFT,
        sidePose(
            trunk = 6f, pelvis = -6f, hip = -80f, knee = 60f, ankle = -20f, shoulder = -95f,
            elbow = -4f, dx = -25.4f, dy = 56.5f, scale = 0.75f,
        ),
        sidePose(
            trunk = -4f, pelvis = 4f, hip = -80f, knee = 60f, ankle = -20f, shoulder = 25f,
            elbow = -110f, dx = -25.4f, dy = 56.5f, scale = 0.75f,
        ),
    )

    val INVERTED_ROW: AnimationClip = clip(
        "INVERTED_ROW", BodyFace.SIDE, LIFT,
        sidePose(
            trunk = -70f, ankle = -20f, shoulder = -90f, elbow = -2f, dx = 0.7f, dy = 89.2f,
            scale = 0.46f,
        ),
        sidePose(
            trunk = -70f, ankle = -20f, shoulder = -20f, elbow = -110f, dx = -1f, dy = 72f,
            scale = 0.46f,
        ),
    )

    val PULL_UP: AnimationClip = clip(
        "PULL_UP", BodyFace.FRONT, LIFT,
        frontPose(
            hip = 4f, knee = -18f, shoulder = 168f, elbow = -6f, dx = -8.7f, dy = 9.7f,
            scale = 0.7f,
        ),
        frontPose(
            hip = 4f, knee = -18f, shoulder = 45f, elbow = 130f, dx = 5f, dy = -34.4f,
            scale = 0.7f,
        ),
    )

    val PULLDOWN: AnimationClip = clip(
        "PULLDOWN", BodyFace.FRONT, LIFT,
        frontPose(shoulder = 165f, elbow = -6f, dy = 32.3f, scale = 0.7f),
        frontPose(shoulder = 60f, elbow = 115f, dy = 32.3f, scale = 0.7f),
    )

    val HANG: AnimationClip = clip(
        "HANG", BodyFace.SIDE, HOLD,
        sidePose(shoulder = -172f, elbow = -2f, dx = -0.2f, dy = -0.2f, scale = 0.7f),
        sidePose(hip = 4f, shoulder = -176f, elbow = -2f, dx = 1.1f, dy = 0.1f, scale = 0.7f),
    )

    val LEG_RAISE: AnimationClip = clip(
        "LEG_RAISE", BodyFace.SIDE, LIFT,
        sidePose(
            hip = 4f, knee = -4f, shoulder = -172f, elbow = -2f, dx = -21.4f, dy = -0.3f,
            scale = 0.7f,
        ),
        sidePose(
            hip = -92f, knee = 6f, shoulder = -172f, elbow = -2f, dx = -21.4f, dy = -0.3f,
            scale = 0.7f,
        ),
    )

    val LATERAL_RAISE: AnimationClip = clip(
        "LATERAL_RAISE", BodyFace.FRONT, LIFT,
        frontPose(shoulder = 6f, elbow = -10f, dy = 59f, scale = 0.45f),
        frontPose(shoulder = 92f, elbow = -8f, dy = 59f, scale = 0.45f),
    )

    val REAR_DELT: AnimationClip = clip(
        "REAR_DELT", BodyFace.BACK, LIFT,
        frontPose(shoulder = 84f, elbow = -118f, dy = 53.6f, scale = 0.5f),
        frontPose(shoulder = 96f, elbow = -14f, dy = 53.6f, scale = 0.5f),
    )

    val FACE_PULL: AnimationClip = clip(
        "FACE_PULL", BodyFace.SIDE, LIFT,
        sidePose(
            trunk = 4f, pelvis = -4f, hip = -10f, knee = 12f, shoulder = -108f, elbow = -2f,
            dx = -30.3f, dy = 21.7f, scale = 0.8f,
        ),
        sidePose(
            trunk = 4f, pelvis = -4f, hip = -10f, knee = 12f, shoulder = -105f, elbow = -125f,
            dx = -30.3f, dy = 21.7f, scale = 0.8f,
        ),
    )

    val CURL: AnimationClip = clip(
        "CURL", BodyFace.SIDE, LIFT,
        sidePose(shoulder = -6f, elbow = -6f, dx = -1.4f, dy = 10.4f, scale = 0.9f),
        sidePose(shoulder = -12f, elbow = -128f, dx = -1.4f, dy = 10.4f, scale = 0.9f),
    )

    val TRICEPS_PUSHDOWN: AnimationClip = clip(
        "TRICEPS_PUSHDOWN", BodyFace.SIDE, LIFT,
        sidePose(
            trunk = 6f, pelvis = -6f, knee = 6f, shoulder = -8f, elbow = -95f, dx = -19.1f,
            dy = 10.4f, scale = 0.9f,
        ),
        sidePose(
            trunk = 6f, pelvis = -6f, knee = 6f, shoulder = -8f, elbow = -6f, dx = -19.1f,
            dy = 10.4f, scale = 0.9f,
        ),
    )

    val SKULL_CRUSHER: AnimationClip = clip(
        "SKULL_CRUSHER", BodyFace.SIDE, LIFT,
        sidePose(
            trunk = -90f, hip = -60f, knee = 100f, ankle = -45f, shoulder = -100f, elbow = -125f,
            dx = 11.6f, dy = 101.6f, scale = 0.48f,
        ),
        sidePose(
            trunk = -90f, hip = -60f, knee = 100f, ankle = -45f, shoulder = -100f, elbow = -4f,
            dx = 10.5f, dy = 101.6f, scale = 0.48f,
        ),
    )

    val SIDE_PLANK: AnimationClip = clip(
        "SIDE_PLANK", BodyFace.FRONT, HOLD,
        frontPose(
            trunk = -76.7f, hip = 3f, shoulder = 95f, elbow = 85f, elbowRight = 0f, dx = 5.4f,
            dy = 89.6f, scale = 0.4f,
        ),
        frontPose(
            trunk = -75.8f, hip = 1f, shoulder = 95f, elbow = 85f, elbowRight = 0f, dx = 5.7f,
            dy = 90.1f, scale = 0.4f,
        ),
        mirror = true,
    )

    val DEAD_BUG: AnimationClip = clip(
        "DEAD_BUG", BodyFace.SIDE, LIFT,
        sidePose(
            trunk = -90f, hip = -88f, knee = 88f, shoulder = -90f, elbow = -4f, dx = 8.1f,
            dy = 101.8f, scale = 0.37f,
        ),
        sidePose(
            trunk = -90f, hip = -14f, knee = 10f, hipFar = -88f, kneeFar = 88f, shoulder = -90f,
            elbow = -4f, shoulderFar = -165f, dx = 8.8f, dy = 101.8f, scale = 0.37f,
        ),
        mirror = true,
    )

    val HOLLOW: AnimationClip = clip(
        "HOLLOW", BodyFace.SIDE, HOLD,
        sidePose(
            trunk = -80f, pelvis = -10f, hip = -18f, ankle = 25f, shoulder = -170f, elbow = -2f,
            neck = 10f, dx = 8.8f, dy = 101.9f, scale = 0.38f,
        ),
        sidePose(
            trunk = -79f, pelvis = -11f, hip = -20f, ankle = 25f, shoulder = -172f, elbow = -2f,
            neck = 12f, dx = 9.1f, dy = 101.9f, scale = 0.38f,
        ),
    )

    val TWIST: AnimationClip = clip(
        "TWIST", BodyFace.FRONT, LIFT,
        frontPose(
            torso = -8f, hip = 22f, knee = -34f, shoulder = -75f, elbow = -20f,
            shoulderRight = -100f, dx = -20.6f, dy = 55.6f, scale = 0.5f,
        ),
        frontPose(
            torso = 8f, hip = 22f, knee = -34f, shoulder = 100f, elbow = -20f, shoulderRight = 75f,
            dx = 20.6f, dy = 55.6f, scale = 0.5f,
        ),
        mirror = true,
    )

    val PALLOF: AnimationClip = clip(
        "PALLOF", BodyFace.SIDE, LIFT,
        sidePose(
            trunk = 8f, pelvis = -8f, hip = -26f, knee = 30f, ankle = -4f, shoulder = -32f,
            elbow = -112f, dx = -33f, dy = 25f, scale = 0.8f,
        ),
        sidePose(
            trunk = 8f, pelvis = -8f, hip = -26f, knee = 30f, ankle = -4f, shoulder = -95f,
            elbow = -2f, dx = -33f, dy = 25f, scale = 0.8f,
        ),
    )

    val BACK_EXTENSION: AnimationClip = clip(
        "BACK_EXTENSION", BodyFace.SIDE, LIFT,
        sidePose(
            trunk = 92f, hip = -2f, ankle = 70f, shoulder = -70f, elbow = -125f, dx = 1.2f,
            dy = 87.8f, scale = 0.42f,
        ),
        sidePose(
            trunk = 68f, hip = 22f, ankle = 70f, shoulder = -70f, elbow = -125f, dx = 0.4f,
            dy = 98.3f, scale = 0.42f,
        ),
    )

    val BIRD_DOG: AnimationClip = clip(
        "BIRD_DOG", BodyFace.SIDE, LIFT,
        sidePose(
            trunk = 88f, hip = -88f, knee = 88f, ankle = 10f, shoulder = -88f, elbow = -2f,
            dx = -8.6f, dy = 76.1f, scale = 0.36f,
        ),
        sidePose(
            trunk = 88f, hip = -88f, knee = 88f, ankle = 10f, hipFar = 2f, kneeFar = 2f,
            shoulder = -88f, elbow = -2f, shoulderFar = -178f, dx = -8.6f, dy = 76.1f,
            scale = 0.36f,
        ),
        mirror = true,
    )
}
