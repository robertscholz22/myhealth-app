package com.myhealth.data.mapper

import com.myhealth.data.db.entity.CycleEntryEntity
import com.myhealth.domain.model.CycleEntry

/** `cycle_entry` ⇄ [CycleEntry] (PLAN §5 P11.1). A plain one-to-one mapping — no derived columns. */
fun CycleEntryEntity.toDomain(): CycleEntry = CycleEntry(
    id = id,
    periodStartDay = periodStartDay,
    periodEndDay = periodEndDay,
    note = note,
    createdAtMillis = createdAtMillis,
    updatedAtMillis = updatedAtMillis,
)

fun CycleEntry.toEntity(): CycleEntryEntity = CycleEntryEntity(
    id = id,
    periodStartDay = periodStartDay,
    periodEndDay = periodEndDay,
    note = note,
    createdAtMillis = createdAtMillis,
    updatedAtMillis = updatedAtMillis,
)
