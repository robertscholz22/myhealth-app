package com.myhealth.data.db.relation

import androidx.room.Embedded
import androidx.room.Relation
import com.myhealth.data.db.entity.ActivityLapEntity
import com.myhealth.data.db.entity.ActivitySessionEntity
import com.myhealth.data.db.entity.ActivityStreamEntity

/**
 * One `activity_session` with its (at most one) `activity_stream` row and its laps — the "full"
 * load used by the activity detail screen and the running-best engine (PLAN §2.3).
 */
data class ActivityWithStream(
    @Embedded val activity: ActivitySessionEntity,
    @Relation(parentColumn = "id", entityColumn = "activityId")
    val stream: ActivityStreamEntity?,
    @Relation(parentColumn = "id", entityColumn = "activityId")
    val laps: List<ActivityLapEntity>,
)
