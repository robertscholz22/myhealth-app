package com.myhealth.ui.common.body

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.myhealth.resources.*
import com.myhealth.ui.common.isReducedMotionEnabled
import com.myhealth.ui.common.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.myhealth.domain.engine.strength.AnimationClip
import com.myhealth.domain.engine.strength.AnimationClips
import com.myhealth.domain.model.BodyFace
import com.myhealth.domain.model.BodyPose
import com.myhealth.domain.model.MuscleGroup
import com.myhealth.ui.theme.MyHealthTheme

/**
 * The looping schematic animation of [clip] (P18.2, PLAN P18 design (4)/(5)): drives [poseAt] with
 * `withFrameNanos` and draws [BodyFigure] for **the clip's face only** ([BodyFigure.faces], added
 * for this). Tapping the figure pauses or resumes it; [playing] is only the *initial* state, so a
 * caller that starts several of these (the set-log sheet's per-exercise header rows) does not fight
 * a user who paused one of them.
 *
 * Honours "Remove animations" (`Settings.Global.ANIMATOR_DURATION_SCALE == 0`): the figure never
 * starts a frame loop and shows [midpointPose] instead — the same still frame [StaticBodyFigure]
 * draws for thumbnails — and the tap target is dropped, since there is nothing to pause.
 *
 * [sizeDp] is the figure's height; the width follows the 100 : 220 box (via [aspectRatio]) so a
 * caller need not work the ratio out itself — the exercise detail screen passes ~220 dp, the
 * set-log sheet ~72 dp.
 */
@Composable
fun AnimatedBodyFigure(
    clip: AnimationClip,
    highlight: Map<MuscleGroup, Float>,
    modifier: Modifier = Modifier,
    playing: Boolean = true,
    sizeDp: Dp = 220.dp,
) {
    val reducedMotion = isReducedMotionEnabled()
    var userPlaying by rememberSaveable(clip.id) { mutableStateOf(playing) }
    val isPlaying = userPlaying && !reducedMotion
    val duration = remember(clip) { clipDurationMs(clip).coerceAtLeast(1) }
    var elapsedMs by remember(clip) { mutableLongStateOf(0L) }

    LaunchedEffect(clip, isPlaying) {
        if (!isPlaying) return@LaunchedEffect
        var lastFrameNanos = -1L
        while (true) {
            withFrameNanos { nowNanos ->
                if (lastFrameNanos >= 0) {
                    val deltaMs = (nowNanos - lastFrameNanos) / NANOS_PER_MILLI
                    elapsedMs = (elapsedMs + deltaMs) % duration
                }
                lastFrameNanos = nowNanos
            }
        }
    }

    val pose = if (reducedMotion) remember(clip) { midpointPose(clip) } else poseAt(clip, elapsedMs)
    val toggleDescription = stringResource(
        if (userPlaying) Res.string.exercise_animation_pause_cd else Res.string.exercise_animation_play_cd,
    )
    val tapModifier = if (reducedMotion) {
        Modifier
    } else {
        Modifier
            .clickable(role = Role.Button) { userPlaying = !userPlaying }
            .semantics { contentDescription = toggleDescription }
    }

    val viewport = remember(clip) { clipViewport(clip) }
    SizedFigureBox(
        pose = pose,
        face = clip.face,
        highlight = highlight,
        sizeDp = sizeDp,
        viewport = viewport,
        modifier = modifier.then(tapModifier),
    )
}

/**
 * A still frame of [clip] at its [midpointPose] (P18.2) — the exercise picker rows and the workout
 * editor's exercise rows want "what does this movement look like" without an animation loop's cost
 * in a scrolling list; [AnimatedBodyFigure]'s reduced-motion fallback draws the same pose.
 */
@Composable
fun StaticBodyFigure(
    clip: AnimationClip,
    highlight: Map<MuscleGroup, Float>,
    modifier: Modifier = Modifier,
    sizeDp: Dp = 56.dp,
) {
    val pose = remember(clip) { midpointPose(clip) }
    val viewport = remember(clip) { clipViewport(clip) }
    SizedFigureBox(pose = pose, face = clip.face, highlight = highlight, sizeDp = sizeDp, viewport = viewport, modifier = modifier)
}

/**
 * [BodyFigure], sized to [sizeDp] tall with the width computed from the 100 : 220 box, one [face].
 *
 * Both dimensions are set **explicitly** (`width(...).height(sizeDp)`), not via `aspectRatio` on a
 * loosely-constrained box: a caller embeds this in all sorts of places — a full-width column item,
 * a `Row` alongside a weighted text column, a bottom sheet header — and [BodyFigure] itself sizes
 * each face with a `weight(1f)` `Box`, which needs a *bounded* incoming width from this box's own
 * `Row` or it throws. An explicit fixed size is bounded regardless of what the caller's layout
 * passes in, where `aspectRatio` would have inherited (and been fought over by) the caller's own
 * width constraint — `fillMaxWidth()` plus a fixed height, in particular, forces an exact width
 * `aspectRatio` cannot then shrink.
 */
@Composable
private fun SizedFigureBox(
    pose: BodyPose,
    face: BodyFace,
    highlight: Map<MuscleGroup, Float>,
    sizeDp: Dp,
    modifier: Modifier = Modifier,
    viewport: BodyViewport = BodyViewport.FULL,
) {
    // POLISH-21: the box takes the viewport's aspect ratio — a horizontal clip gets a landscape
    // box (capped at MAX_WIDTH_FACTOR × sizeDp so it never outgrows a phone's width) — and the
    // figure is scaled uniformly inside it, so a plank is drawn as large as a standing figure.
    val ratio = viewport.width / viewport.height
    val widthDp = (sizeDp * ratio).coerceAtMost(sizeDp * MAX_WIDTH_FACTOR)
    Box(modifier = modifier.width(widthDp).height(sizeDp), contentAlignment = Alignment.Center) {
        BodyFigure(
            highlight = highlight,
            pose = pose,
            faces = listOf(face),
            viewport = viewport,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/** A horizontal clip may be at most this many times wider than it is tall (≈ 1.5 × 220 dp on a phone). */
private const val MAX_WIDTH_FACTOR = 1.5f


private const val NANOS_PER_MILLI = 1_000_000L

@Preview(showBackground = true)
@Composable
private fun AnimatedBodyFigurePreview() {
    MyHealthTheme(dynamicColor = false) {
        AnimatedBodyFigure(
            clip = AnimationClips.SQUAT,
            highlight = mapOf(MuscleGroup.QUADS to 1.0f, MuscleGroup.GLUTES to 1.0f),
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun StaticBodyFigurePreview() {
    MyHealthTheme(dynamicColor = false) {
        StaticBodyFigure(
            clip = AnimationClips.PUSH_UP,
            highlight = mapOf(MuscleGroup.CHEST to 1.0f, MuscleGroup.TRICEPS to 0.35f),
        )
    }
}
