package com.myhealth.ui.common

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import java.text.DecimalFormatSymbols
import java.util.Locale
import kotlin.random.Random

/**
 * P20.3: [ResourceFormat] (the iOS formatter for string resources) against `String.format`, which
 * Android keeps using, on every specifier the app's strings contain, in three locales.
 */
class ResourceFormatTest {

    private val locales = listOf(Locale.US, Locale.GERMANY, Locale.FRANCE)

    private fun check(pattern: String, vararg args: Any) {
        for (locale in locales) {
            val separator = DecimalFormatSymbols.getInstance(locale).decimalSeparator
            assertWithMessage("%s", "$pattern ${args.toList()} $locale")
                .that(ResourceFormat.format(pattern, args, separator))
                .isEqualTo(String.format(locale, pattern, *args))
        }
    }

    @Test
    fun rf01_specifiers_used_by_the_strings() {
        check("%1\$s · %2\$d", "Run", 42)
        check("%1\$d / %2\$d kcal", 0, -2510)
        check("%1\$.0f AU, %2\$.1f km, %3\$.0f%%", 219.5, 10.25, 99.5)
        check("%3\$s %1\$s %2\$s", "a", "b", "c")
        check("%1\$s and %1\$s", "x")
        check("100%% done %1\$d", 1)
        check("%s / %d / %.2f", "plain", 7L, 1.005)
        check("%1\$.1f", 1.5f)
        check("%1\$s", 2.5)
        check("%1\$.1f %2\$.0f", -0.04, Double.NaN)
    }

    @Test
    fun rf02_random_values() {
        val random = Random(203)
        repeat(20_000) {
            val v = (random.nextDouble() - 0.5) * 10.0.pow(random.nextInt(-3, 7))
            check("%1\$.0f|%1\$.1f|%1\$.2f|%2\$d", v, random.nextInt())
        }
    }

    @Test
    fun rf03_wrong_argument_types_fail_like_java() {
        listOf("%1\$d" to arrayOf<Any>(1.5), "%1\$.1f" to arrayOf<Any>(3), "%2\$s" to arrayOf<Any>("only one"), "%1\$x" to arrayOf<Any>("a"))
            .forEach { (pattern, args) ->
                assertThat(runCatching { String.format(Locale.US, pattern, *args) }.isFailure).isTrue()
                assertThat(runCatching { ResourceFormat.format(pattern, args) }.exceptionOrNull())
                    .isInstanceOf(IllegalArgumentException::class.java)
            }
    }

    private fun Double.pow(n: Int) = Math.pow(this, n.toDouble())
}
