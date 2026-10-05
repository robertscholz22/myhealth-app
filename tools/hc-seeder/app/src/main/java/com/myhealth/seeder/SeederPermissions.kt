package com.myhealth.seeder

/** Every permission this seeder needs: WRITE so it can insert data, READ so it can count what it
 * actually wrote back out (the acceptance check for "SEED DONE: <counts>"). The
 * androidx.health.connect.client.permission.HealthPermission companion's WRITE_ and READ_ string
 * constants are `internal` in connect-client 1.1.0, so the literal permission strings (which are
 * public API / stable, per the Health Connect docs) are used directly instead. */
object SeederPermissions {
    val ALL: Set<String> = setOf(
        "android.permission.health.WRITE_EXERCISE",
        "android.permission.health.WRITE_STEPS",
        "android.permission.health.WRITE_DISTANCE",
        "android.permission.health.WRITE_SPEED",
        "android.permission.health.WRITE_HEART_RATE",
        "android.permission.health.WRITE_RESTING_HEART_RATE",
        "android.permission.health.WRITE_HEART_RATE_VARIABILITY",
        "android.permission.health.WRITE_SLEEP",
        "android.permission.health.WRITE_WEIGHT",
        "android.permission.health.WRITE_BODY_FAT",
        "android.permission.health.WRITE_TOTAL_CALORIES_BURNED",
        "android.permission.health.WRITE_ACTIVE_CALORIES_BURNED",
        "android.permission.health.WRITE_FLOORS_CLIMBED",
        "android.permission.health.WRITE_ELEVATION_GAINED",
        "android.permission.health.WRITE_OXYGEN_SATURATION",
        "android.permission.health.WRITE_RESPIRATORY_RATE",
        "android.permission.health.WRITE_VO2_MAX",
        "android.permission.health.WRITE_POWER",
        "android.permission.health.READ_EXERCISE",
        "android.permission.health.READ_STEPS",
        "android.permission.health.READ_DISTANCE",
        "android.permission.health.READ_SPEED",
        "android.permission.health.READ_HEART_RATE",
        "android.permission.health.READ_RESTING_HEART_RATE",
        "android.permission.health.READ_HEART_RATE_VARIABILITY",
        "android.permission.health.READ_SLEEP",
        "android.permission.health.READ_WEIGHT",
        "android.permission.health.READ_BODY_FAT",
        "android.permission.health.READ_TOTAL_CALORIES_BURNED",
        "android.permission.health.READ_ACTIVE_CALORIES_BURNED",
        "android.permission.health.READ_FLOORS_CLIMBED",
        "android.permission.health.READ_ELEVATION_GAINED",
        "android.permission.health.READ_OXYGEN_SATURATION",
        "android.permission.health.READ_RESPIRATORY_RATE",
        "android.permission.health.READ_VO2_MAX",
        "android.permission.health.READ_POWER",
    )
}
