package com.myhealth.ui.ai

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.myhealth.di.appGraph
import com.myhealth.ui.share.LocalShareBadgesEnabled

/**
 * P28.5: ids of the planned sessions the AI created, for the "Planned with AI" line. Gated like the
 * share badges: only inside the app, where the graph exists — not in previews and content tests.
 */
@Composable
fun aiSessionIds(): Set<Long> {
    if (!LocalShareBadgesEnabled.current) return emptySet()
    val keys by appGraph().aiPlanning.createdByAi.collectAsStateWithLifecycle(emptySet())
    return remember(keys) { keys.mapNotNullTo(mutableSetOf()) { k -> k.removePrefix("SESSION:").takeIf { it != k }?.toLongOrNull() } }
}
