package com.myhealth.domain.repository

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

/**
 * Guards the repository contract of PLAN §1.4 / P1.7 by parsing the sources in `domain/repository`:
 * inside an `interface`, every member must be a `suspend fun` or return a `Flow` — there are no
 * blocking getters — and every write must return an [com.myhealth.domain.util.Outcome].
 *
 * Source parsing (rather than reflection) is deliberate: it sees the declaration as written,
 * including the return type, and reports the offending line verbatim.
 */
class RepositoryContractTest {

    @Test
    fun every_planned_repository_interface_exists() {
        val present = repositoryFiles().map { it.nameWithoutExtension }.toSet()

        assertThat(present).containsAtLeastElementsIn(EXPECTED_INTERFACES)
    }

    @Test
    fun every_interface_member_is_a_flow_or_a_suspend_function() {
        val offenders = repositoryFiles().flatMap { file ->
            interfaceMembers(file).filterNot { it.isFlowOrSuspend() }.map { "${file.name}: $it" }
        }

        assertThat(offenders).isEmpty()
    }

    @Test
    fun every_write_returns_an_outcome() {
        // SettingsRepository is exempt by design (§1.5): DataStore writes are declared
        // never-failing, like the cached-data flows, so its setters return Unit.
        val offenders = repositoryFiles().filterNot { it.name == "SettingsRepository.kt" }.flatMap { file ->
            interfaceMembers(file)
                .filter { it.startsWith("suspend fun") && it.isWrite() }
                .filterNot { it.contains(": Outcome<") }
                .map { "${file.name}: $it" }
        }

        assertThat(offenders).isEmpty()
    }

    @Test
    fun repository_interfaces_declare_at_least_one_member_each() {
        val empty = repositoryFiles().filter { interfaceMembers(it).isEmpty() }.map { it.name }

        assertThat(empty).isEmpty()
    }

    // ---- parsing --------------------------------------------------------------------------------

    /** Members declared directly inside an `interface` body, each collapsed to a single line. */
    private fun interfaceMembers(file: File): List<String> {
        val members = mutableListOf<String>()
        var depth = 0
        var interfaceDepth = -1
        val pending = StringBuilder()

        for (raw in file.readLines()) {
            val line = raw.substringBefore("//").trim()
            if (line.isEmpty() || line.startsWith("*") || line.startsWith("/*")) continue

            if (interfaceDepth < 0 && INTERFACE_HEADER.containsMatchIn(line)) interfaceDepth = depth

            val insideInterface = interfaceDepth >= 0 && depth == interfaceDepth + 1
            if (insideInterface && (pending.isNotEmpty() || MEMBER_START.containsMatchIn(line))) {
                if (pending.isNotEmpty()) pending.append(' ')
                pending.append(line)
                if (pending.isBalanced()) {
                    members += pending.toString()
                    pending.clear()
                }
            }

            depth += line.count { it == '{' } - line.count { it == '}' }
            if (interfaceDepth >= 0 && depth <= interfaceDepth) interfaceDepth = -1
        }
        return members
    }

    /** A declaration is complete once its parentheses and type brackets close (`->` is not one). */
    private fun StringBuilder.isBalanced(): Boolean {
        val text = toString().replace("->", "")
        return text.count { it == '(' } == text.count { it == ')' } &&
            text.count { it == '<' } == text.count { it == '>' }
    }

    private fun String.isFlowOrSuspend(): Boolean =
        startsWith("suspend fun ") || ((startsWith("fun ") || startsWith("val ")) && contains(": Flow<"))

    private fun String.isWrite(): Boolean {
        val name = substringAfter("suspend fun ").substringBefore('(').trim()
        return WRITE_PREFIXES.any { name.startsWith(it) }
    }

    private fun repositoryFiles(): List<File> =
        checkNotNull(repositoryDir.listFiles { f: File -> f.extension == "kt" }) {
            "No Kotlin files under $repositoryDir"
        }.sortedBy { it.name }

    private companion object {
        val INTERFACE_HEADER = Regex("""^(?:\w+ )*interface \w+""")
        val MEMBER_START = Regex("""^(suspend fun|fun|val|var) """)

        /** Name prefixes that mean "this call mutates storage" (§1.5: those return `Outcome`). */
        val WRITE_PREFIXES = listOf(
            "upsert", "insert", "delete", "set", "mark", "add", "update", "record", "link",
            "accept", "reject", "generate", "ensure", "recompute", "log", "copy", "replace",
            "ingest", "supersede", "archive",
        )

        val EXPECTED_INTERFACES = listOf(
            "ProfileRepository", "BodyRepository", "ActivityRepository", "HealthRepository",
            "CalendarRepository", "PlanRepository", "GoalRepository", "NutritionRepository",
            "IngredientRepository", "MealRepository", "LoadRepository", "RunningBestRepository",
            "SettingsRepository", "SyncStateRepository", "ImportRepository", "SuggestionRepository",
            "CycleRepository",
        )

        /** `shared/src/commonMain/kotlin/com/myhealth/domain/repository` (P20.1), from module dir or project root. */
        val repositoryDir: File = run {
            val userDir = File(System.getProperty("user.dir") ?: ".").absoluteFile
            val suffix = "shared/src/commonMain/kotlin/com/myhealth/domain/repository"
            listOf(File(userDir, suffix), File(userDir.parentFile, suffix))
                .firstOrNull { it.isDirectory }
                ?: error("Could not locate $suffix from user.dir=$userDir")
        }
    }
}
