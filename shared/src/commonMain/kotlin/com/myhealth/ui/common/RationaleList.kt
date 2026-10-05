package com.myhealth.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.myhealth.domain.model.RationaleEntry
import com.myhealth.ui.theme.MyHealthTheme

/**
 * The "why this session" bullets of a suggestion (§4.3 `RationaleList(items)`, PLAN §3.5.6 step 8).
 *
 * Every entry is shown — the rationale is the whole point of proposing rather than imposing, so it
 * is never truncated behind a "show more". The rule id is not rendered: it is a stable key for
 * tests and logs, not something the reader needs.
 */
@Composable
fun RationaleList(items: List<RationaleEntry>, modifier: Modifier = Modifier) {
    if (items.isEmpty()) return
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        items.forEach { entry ->
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "•",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(end = 8.dp),
                )
                Text(
                    text = entry.text,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun RationaleListPreview() {
    MyHealthTheme(dynamicColor = false) {
        RationaleList(
            items = listOf(
                RationaleEntry("PHASE_BUILD", "Build phase: tempo work develops threshold for your 5k goal"),
                RationaleEntry("BUDGET", "Weekly load target 620 AU; 180 AU still unallocated"),
                RationaleEntry("RECOVERY_GOOD", "Recovery 72/100, you can absorb a quality session"),
            ),
            modifier = Modifier.padding(16.dp),
        )
    }
}
