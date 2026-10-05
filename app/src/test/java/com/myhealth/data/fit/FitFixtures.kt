package com.myhealth.data.fit

import kotlinx.serialization.json.Json
import java.io.File

/**
 * Loads `app/src/test/resources/fixtures/fit/<name>.json` off the classpath (rule R12: `user.dir`
 * is the module directory for Gradle unit tests, so a relative file path is not safe).
 *
 * The fixtures are shaped exactly like [FitFileData] — the SDK-free data class the mapper
 * consumes — because a binary `.fit` file cannot be authored by hand (PLAN P7.2).
 */
object FitFixtures {

    private val json = Json { ignoreUnknownKeys = true }

    fun load(name: String): FitFileData = json.decodeFromString(text(name))

    fun text(name: String): String {
        val path = "fixtures/fit/$name.json"
        val url = checkNotNull(FitFixtures::class.java.classLoader).getResource(path)
            ?: error("Missing FIT fixture on the classpath: $path")
        return url.readText()
    }

    /** `app/src/test/resources/fixtures/fit`, resolved from the module dir or the project root. */
    fun resourceDir(): File {
        val userDir = File(System.getProperty("user.dir") ?: ".").absoluteFile
        val suffix = "src/test/resources/fixtures/fit"
        return listOf(File(userDir, suffix), File(userDir, "app/$suffix"))
            .firstOrNull { it.isDirectory }
            ?: error("Could not locate $suffix from user.dir=$userDir")
    }

    /** `docs/testassets`, created on demand — where the lead's emulator asset is published. */
    fun docsAssetDir(): File {
        val userDir = File(System.getProperty("user.dir") ?: ".").absoluteFile
        val root = if (File(userDir, "docs").isDirectory) userDir else userDir.parentFile
        return File(root, "docs/testassets").also { it.mkdirs() }
    }
}
