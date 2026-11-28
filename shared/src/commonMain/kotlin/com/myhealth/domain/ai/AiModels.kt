package com.myhealth.domain.ai

/**
 * P28: what the phone may sync to the AI relay. [BASICS] is always on — no plan can be made without
 * the exercises, workouts and calendar — the other three are the owner's toggles.
 */
enum class AiDataGroup {
    /** Exercises, my equipment, workouts, goals, planned sessions, calendar events, done activities. */
    BASICS,

    /** Daily load (CTL, ATL, ACWR), weekly load, strength progress per exercise. */
    LOAD,

    /** Age, sex, weight, resting and max HR, HR zones, sleep and readiness. */
    BODY,

    /** Cycle phase today and the predicted period days ahead. */
    CYCLE,
    ;

    companion object {
        val ALL: Set<AiDataGroup> = entries.toSet()

        /** The stored set, with [BASICS] always included and unknown names ignored. */
        fun parse(names: Collection<String>): Set<AiDataGroup> =
            names.mapNotNull { n -> entries.firstOrNull { it.name == n } }.toSet() + BASICS
    }
}

/** The kinds of local rows the AI can create and therefore later change. */
enum class AiKind { EXERCISE, WORKOUT, SESSION }

/**
 * One row the AI created: its id in the conversation ([aiId], `ai-…`) and the local row. Only rows
 * with a link may be changed or deleted by the AI (besides unlocked, not-done planned sessions).
 */
data class AiLink(
    val aiId: String,
    val kind: AiKind,
    val localKey: String,
    val createdAtMillis: Long,
)

/** The relay connection the phone keeps. */
data class AiConnection(val relayUrl: String, val registrationKey: String)

/** The phone's registered space on the relay. */
data class AiSetup(
    val relayUrl: String,
    val spaceId: String,
    val spaceToken: String,
    val groups: Set<AiDataGroup>,
    val lastSyncAtMillis: Long?,
    val connectedAtMillis: Long,
)

/** Prefix of ids the AI gives the rows it creates. */
const val AI_ID_PREFIX: String = "ai-"

/** Snapshot ids of local rows: `w12` for workout 12, `s34` for planned session 34. */
object AiIds {
    fun workout(id: Long): String = "w$id"
    fun session(id: Long): String = "s$id"

    fun parseWorkout(ref: String): Long? = ref.takeIf { it.startsWith("w") }?.drop(1)?.toLongOrNull()
    fun parseSession(ref: String): Long? = ref.takeIf { it.startsWith("s") }?.drop(1)?.toLongOrNull()

    fun isAi(ref: String): Boolean = ref.startsWith(AI_ID_PREFIX)
}
