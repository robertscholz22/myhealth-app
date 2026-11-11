package com.myhealth.domain.engine.calendar

import com.myhealth.domain.model.CalendarClusterRule
import com.myhealth.domain.model.DeviceCalendarEntry
import com.myhealth.domain.model.EventType

/** What made a group a group. */
enum class ClusterKind {
    /** "A - B" entries that share one side (the own team) against changing opponents. */
    FIXTURES,

    /** Entries with the same title. */
    REPEATED,

    /** Everything that fits no group. */
    OTHER,
}

/**
 * One group of calendar entries. [key] is stable for the same kind of entry across runs, so a
 * rule stored under it keeps applying; [name] is the shared side or title as the calendar wrote it
 * (empty for [ClusterKind.OTHER]).
 */
data class EntryCluster(
    val key: String,
    val kind: ClusterKind,
    val name: String,
    val entries: List<DeviceCalendarEntry>,
)

/**
 * Groups a calendar's entries by what they are, from the titles alone:
 *
 * 1. Fixtures: titles of the form "A - B" (also "vs", "gegen", ":") where one side meets at least
 *    two different other sides. That side is the own team; all its pairings form one group.
 * 2. Repeated: the same title at least twice.
 * 3. Other: the rest, in one group.
 *
 * [knownKeys] are group keys that already carry a rule: such a group stays a group even when the
 * calendar currently holds a single entry of it, so a rule does not stop applying in a thin week.
 */
object CalendarClusterer {

    const val OTHER_KEY: String = "other"
    private const val FIXTURE_PREFIX = "f:"
    private const val TITLE_PREFIX = "t:"

    private val WHITESPACE = Regex("\\s+")
    private val VERSUS = Regex("^(.+?)\\s+(?:-|–|—|vs\\.?|gegen|:)\\s+(.+)$", RegexOption.IGNORE_CASE)
    private val TRAINING_WORDS = listOf("training", "practice", "übung", "einheit")
    private val MATCH_WORDS = listOf("spiel", "match", "turnier", "pokal", "cup", "game")

    /** Lower case, single spaces, no trailing punctuation: what makes two titles "the same". */
    fun normalize(title: String): String =
        title.replace(WHITESPACE, " ").trim().trimEnd(',', ';', '.', '-', ' ').lowercase()

    fun cluster(entries: List<DeviceCalendarEntry>, knownKeys: Set<String> = emptySet()): List<EntryCluster> {
        val unique = entries
            .filter { it.title.isNotBlank() }
            .distinctBy { normalize(it.title) to it.startMillis }
            .sortedBy { it.startMillis }
        val byTitle = unique.groupBy { normalize(it.title) }.toMutableMap()
        val clusters = mutableListOf<EntryCluster>()

        // 1. fixtures
        val sides = byTitle.keys.mapNotNull { title ->
            VERSUS.matchEntire(title)?.let { m -> title to listOf(m.groupValues[1].trim(), m.groupValues[2].trim()) }
        }.toMap().toMutableMap()
        while (true) {
            val opponents = mutableMapOf<String, MutableSet<String>>()
            sides.values.forEach { (left, right) ->
                opponents.getOrPut(left) { mutableSetOf() } += right
                opponents.getOrPut(right) { mutableSetOf() } += left
            }
            val team = opponents
                .filter { (side, others) -> others.size >= 2 || FIXTURE_PREFIX + side in knownKeys }
                .entries
                .sortedWith(compareByDescending<Map.Entry<String, Set<String>>> { it.value.size }.thenBy { it.key })
                .firstOrNull()?.key ?: break
            val titles = sides.filterValues { team in it }.keys
            val members = titles.flatMap { byTitle.remove(it).orEmpty() }.sortedBy { it.startMillis }
            titles.forEach { sides.remove(it) }
            clusters += EntryCluster(FIXTURE_PREFIX + team, ClusterKind.FIXTURES, originalSide(members, team), members)
        }

        // 2. repeated titles
        val repeated = byTitle
            .filter { (title, members) -> members.size >= 2 || TITLE_PREFIX + title in knownKeys }
            .entries
            .sortedWith(compareByDescending<Map.Entry<String, List<DeviceCalendarEntry>>> { it.value.size }.thenBy { it.key })
        repeated.forEach { (title, members) ->
            byTitle.remove(title)
            clusters += EntryCluster(TITLE_PREFIX + title, ClusterKind.REPEATED, displayTitle(members), members)
        }

        // 3. the rest
        val rest = byTitle.values.flatten().sortedBy { it.startMillis }
        if (rest.isNotEmpty()) clusters += EntryCluster(OTHER_KEY, ClusterKind.OTHER, "", rest)
        return clusters
    }

    /**
     * The rule a group starts with: fixtures and match-like titles as matches, training-like titles
     * as practice, both switched on; anything else as an appointment, switched off.
     */
    fun suggestedRule(cluster: EntryCluster): CalendarClusterRule {
        val name = cluster.name.lowercase()
        return when {
            cluster.kind == ClusterKind.FIXTURES -> CalendarClusterRule(enabled = true, type = EventType.SOCCER_MATCH)
            cluster.kind == ClusterKind.REPEATED && TRAINING_WORDS.any { it in name } ->
                CalendarClusterRule(enabled = true, type = EventType.SOCCER_TRAINING)
            cluster.kind == ClusterKind.REPEATED && MATCH_WORDS.any { it in name } ->
                CalendarClusterRule(enabled = true, type = EventType.SOCCER_MATCH)
            else -> CalendarClusterRule(enabled = false, type = EventType.APPOINTMENT)
        }
    }

    private fun displayTitle(members: List<DeviceCalendarEntry>): String =
        members.first().title.replace(WHITESPACE, " ").trim().trimEnd(',', ';', '.', '-', ' ')

    /** [team] in the calendar's own spelling, taken from the first title that contains it. */
    private fun originalSide(members: List<DeviceCalendarEntry>, team: String): String {
        val title = members.firstOrNull()?.title?.replace(WHITESPACE, " ")?.trim() ?: return team
        val at = title.lowercase().indexOf(team)
        return if (at >= 0 && at + team.length <= title.length) title.substring(at, at + team.length) else team
    }
}
