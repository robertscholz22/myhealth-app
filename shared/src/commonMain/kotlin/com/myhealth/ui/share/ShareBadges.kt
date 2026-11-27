package com.myhealth.ui.share

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.myhealth.di.appGraph
import com.myhealth.domain.share.ShareKind
import com.myhealth.resources.*
import com.myhealth.ui.common.stringResource

/**
 * P27.6: the list badge per local key of [kind] — "by Anna" on someone else's items, "Shared" on
 * mine that are in the library. Rows without a badge are not in the map.
 */
@Composable
fun rememberShareBadges(kind: ShareKind): Map<String, String> {
    val repo = appGraph().shareRepo
    val links by repo.links.collectAsStateWithLifecycle(emptyList())
    val setup by repo.setup.collectAsStateWithLifecycle(null)
    val shared = stringResource(Res.string.share_badge_shared)
    val byFormat = stringResource(Res.string.share_by, "\u0000")
    return remember(links, setup, shared, byFormat) {
        val me = setup?.uid
        links.filter { it.kind == kind }.mapNotNull { link ->
            when {
                link.isTheirs(me) -> link.localKey to byFormat.replace("\u0000", link.authorName.orEmpty())
                link.isMineShared(me) -> link.localKey to shared
                else -> null
            }
        }.toMap()
    }
}

/** Set by the nav host, where the app graph exists; previews and content tests leave it off. */
val LocalShareBadgesEnabled = androidx.compose.runtime.staticCompositionLocalOf { false }

/** [rememberShareBadges] inside the app, nothing in previews and content tests. */
@Composable
fun shareBadges(kind: ShareKind): Map<String, String> =
    if (LocalShareBadgesEnabled.current) rememberShareBadges(kind) else emptyMap()
