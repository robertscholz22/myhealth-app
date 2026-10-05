package com.myhealth

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

/**
 * Enforces the layering contract of PLAN §1.2 / rule R6 by reading the sources, not the
 * bytecode — a source scan catches a violation even when the offending file would still compile.
 *
 * `user.dir` is the Gradle module directory (`app/`) for unit tests, but a run from the project
 * root is just as plausible, so both are resolved.
 */
class ArchitectureTest {

    @Test
    fun domain_never_imports_android_androidx_or_other_layers() {
        val offenders = kotlinFilesUnder("domain").flatMap { file ->
            file.readLines()
                .filter { FORBIDDEN_IN_DOMAIN.containsMatchIn(it) }
                .map { "${file.name}: ${it.trim()}" }
        }

        assertThat(offenders).isEmpty()
    }

    @Test
    fun ui_never_imports_the_data_layer() {
        val offenders = kotlinFilesUnder("ui").flatMap { file ->
            file.readLines()
                .filter { it.trimStart().startsWith("import com.myhealth.data.") }
                // P20.3: the injected clock (`PlatformClock`, `today()`) is a platform type, not data access.
                .filterNot { it.trimStart().startsWith("import com.myhealth.data.time.") }
                .map { "${file.name}: ${it.trim()}" }
        }

        assertThat(offenders).isEmpty()
    }

    @Test
    fun garmin_client_is_reachable_only_from_di_and_its_own_package() {
        val offenders = kotlinFilesUnder(".")
            .filterNot { it.isUnder("di") || it.isUnder("data/garmin") }
            .flatMap { file ->
                file.readLines()
                    .filter { it.trimStart().startsWith("import com.myhealth.data.garmin") }
                    .map { "${file.relativeTo(sourceRoot).path}: ${it.trim()}" }
            }

        assertThat(offenders).isEmpty()
    }

    @Test
    fun the_scan_actually_sees_the_sources() {
        // Guards the three tests above against silently passing on an empty file list.
        assertThat(kotlinFilesUnder("domain").size).isAtLeast(100)
        assertThat(sharedRoot.isDirectory).isTrue()
        assertThat(kotlinFilesUnder("ui")).isNotEmpty()
    }

    private fun File.isUnder(relativePath: String): Boolean =
        relativeTo(sourceRoot).invariantPath.startsWith("$relativePath/")

    private fun kotlinFilesUnder(relativePath: String): List<File> {
        val roots = if (relativePath == ".") {
            listOf(sourceRoot)
        } else {
            // P20.1: `domain` lives in the shared module; the app keeps only its java.time twins.
            listOf(File(sourceRoot, relativePath), File(sharedRoot, relativePath))
        }
        return roots.flatMap { root -> root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }
    }

    @Test
    fun shared_common_code_uses_no_jvm_only_api() {
        val offenders = sharedRoot.walkTopDown().filter { it.isFile && it.extension == "kt" }.flatMap { file ->
            file.readLines()
                .filter { JVM_ONLY.containsMatchIn(it) }
                .map { "${file.name}: ${it.trim()}" }
        }.toList()

        assertThat(offenders).isEmpty()
    }

    private val File.invariantPath: String get() = path.replace(File.separatorChar, '/')

    private companion object {
        val FORBIDDEN_IN_DOMAIN =
            Regex("""^import (android|androidx|kotlinx\.coroutines\.android|com\.myhealth\.(data|ui|di))\.""")

        /**
         * Room, SQLite and DataStore (P20.2) and Compose, lifecycle and navigation (P20.3, the
         * JetBrains multiplatform builds keep the androidx packages) are Kotlin Multiplatform
         * libraries; the rest of androidx is not.
         */
        val JVM_ONLY = Regex(
            """^import (java\.|javax\.|android\.|androidx\.(?!room\.|sqlite\.|datastore\.core\.|datastore\.preferences\.core\.|compose\.(runtime|foundation|ui|material3|material\.icons|animation)\.|lifecycle\.(ViewModel|viewModelScope|compose|viewmodel|SavedStateHandle|createSavedStateHandle)|navigation\.))""",
        )

        /** `shared/src/commonMain/kotlin/com/myhealth` (P20.1). */
        val sharedRoot: File by lazy { File(sourceRoot, "../../../../../../shared/src/commonMain/kotlin/com/myhealth").normalize() }

        /** `app/src/main/java/com/myhealth`, from either the module dir or the project root. */
        val sourceRoot: File = run {
            val userDir = File(System.getProperty("user.dir") ?: ".").absoluteFile
            val suffix = "src/main/java/com/myhealth"
            listOf(File(userDir, suffix), File(userDir, "app/$suffix"), File(userDir.parentFile, "app/$suffix"))
                .firstOrNull { it.isDirectory }
                ?: error("Could not locate $suffix from user.dir=$userDir")
        }
    }
}
