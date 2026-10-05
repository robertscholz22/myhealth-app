package com.myhealth.data.healthconnect

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

/**
 * PLAN P2.1. Health Connect silently ignores a permission request for something the manifest does
 * not declare, so the two lists have to stay identical — that is exactly the kind of mismatch that
 * only shows up on a device, hence this test.
 *
 * `user.dir` is the Gradle module directory (`app/`) for unit tests, but a run from the project
 * root is just as plausible, so both are resolved (R12).
 */
class HcPermissionsTest {

    @Test
    fun every_requested_permission_is_a_health_read_permission() {
        assertThat(HcPermissions.ALL).isNotEmpty()
        HcPermissions.ALL.forEach { permission ->
            assertThat(permission).startsWith("android.permission.health.READ_")
        }
        assertThat(HcPermissions.ALL).contains(HcPermissions.HISTORY)
        assertThat(HcPermissions.ALL).contains(HcPermissions.BACKGROUND)
        assertThat(HcPermissions.REQUIRED_CORE).doesNotContain(HcPermissions.HISTORY)
        assertThat(HcPermissions.REQUIRED_CORE).doesNotContain(HcPermissions.BACKGROUND)
    }

    @Test
    fun the_manifest_declares_exactly_the_permissions_the_app_requests() {
        val declared = MANIFEST_PERMISSION.findAll(manifest.readText())
            .map { it.groupValues[1] }
            .toSet()

        assertThat(declared).isEqualTo(HcPermissions.ALL)
    }

    @Test
    fun exercise_routes_are_never_requested() {
        // Amendment A6: GPS comes from the FIT import, not from Health Connect.
        assertThat(HcPermissions.ALL.none { it.contains("EXERCISE_ROUTE") }).isTrue()
        assertThat(manifest.readText()).doesNotContain("EXERCISE_ROUTE")
    }

    @Test
    fun required_core_gates_are_consistent() {
        assertThat(HcPermissions.hasRequiredCore(HcPermissions.ALL)).isTrue()
        assertThat(HcPermissions.hasRequiredCore(emptySet())).isFalse()
        assertThat(HcPermissions.missingRequiredCore(HcPermissions.ALL)).isEmpty()
        assertThat(HcPermissions.missingRequiredCore(emptySet()))
            .isEqualTo(HcPermissions.REQUIRED_CORE)
    }

    private companion object {
        val MANIFEST_PERMISSION =
            Regex("""<uses-permission android:name="(android\.permission\.health\.[A-Z0-9_]+)"""")

        val manifest: File = run {
            val userDir = File(System.getProperty("user.dir") ?: ".").absoluteFile
            val suffix = "src/main/AndroidManifest.xml"
            listOf(File(userDir, suffix), File(userDir, "app/$suffix"))
                .firstOrNull { it.isFile }
                ?: error("Could not locate $suffix from user.dir=$userDir")
        }
    }
}
