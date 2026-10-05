package com.myhealth.di

/**
 * UI-safe mirror of the data layer's `HcStatus` (PLAN P2.8): `ui/` must never import `com.myhealth.data.*`
 * (enforced by `ArchitectureTest`), so the Integrations screen sees this `di`-owned copy instead.
 */
enum class HcStatus { AVAILABLE, UPDATE_REQUIRED, UNAVAILABLE }

/**
 * What the Integrations screen needs from Health Connect, without touching `data.healthconnect`
 * types directly (P2.8). `di/` is allowed to reference `data/` (§1.2), so this interface — and its
 * Android implementation `HealthConnectIntegration` — is the seam. The permission request itself is
 * an activity-result contract, so it goes through `PlatformUi` (P20.3).
 */
interface HcIntegration {
    fun status(): HcStatus
    suspend fun granted(): Set<String>
    val allPermissions: Set<String>

    /** The per-session detail permissions (P12: power) that are optional for sync. */
    val optionalDetailPermissions: Set<String>

    /**
     * Apple Health never tells an app whether *read* access was granted, so on iOS [granted]
     * means "the permission sheet has been answered" and the screen shows no per-type rows.
     */
    val platform: HealthPlatform get() = HealthPlatform.HEALTH_CONNECT
}

/** Which health store the platform reads: Health Connect on Android, Apple Health on iOS (P22). */
enum class HealthPlatform { HEALTH_CONNECT, APPLE_HEALTH }
