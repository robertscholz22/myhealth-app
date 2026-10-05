package com.myhealth.ui.common

import com.myhealth.domain.util.NumberFormat

/**
 * The part of `java.util.Formatter` the string resources use, in common code (P20.3; iOS formats
 * with it, Android keeps `String.format`): `%s`, `%d`, `%f` with an optional `n$` argument index and
 * `.n` precision, and `%%`. `%f` rounds like Java (half-up on the shortest decimal representation)
 * and uses [decimalSeparator]. Another conversion, or an argument of the wrong type, throws
 * [IllegalArgumentException] as `String.format` would. `ResourceFormatTest` checks it against Java.
 */
object ResourceFormat {

    private val SPEC = Regex("""%(?:(\d+)\$)?(?:\.(\d+))?([a-zA-Z%])""")

    fun format(pattern: String, args: Array<out Any?>, decimalSeparator: Char = '.'): String {
        var next = 0
        return SPEC.replace(pattern) { match ->
            val (index, precision, conversion) = match.destructured
            if (conversion == "%") {
                require(index.isEmpty() && precision.isEmpty()) { "illegal %% specifier" }
                return@replace "%"
            }
            val position = if (index.isEmpty()) next++ else index.toInt() - 1
            if (position !in args.indices) throw IllegalArgumentException("missing format argument ${match.value}")
            val arg = args[position]
            when (conversion) {
                "s" -> require(precision.isEmpty()) { "precision on %s" }.let { arg.toString() }
                "d" -> {
                    require(precision.isEmpty()) { "precision on %d" }
                    when (arg) {
                        is Int, is Long, is Short, is Byte -> arg.toString()
                        else -> throw IllegalArgumentException("d != ${arg?.let { it::class.simpleName }}")
                    }
                }
                "f" -> {
                    val value = when (arg) {
                        is Double -> arg
                        is Float -> arg.toDouble()
                        else -> throw IllegalArgumentException("f != ${arg?.let { it::class.simpleName }}")
                    }
                    val text = NumberFormat.fixed(value, if (precision.isEmpty()) 6 else precision.toInt())
                    if (decimalSeparator == '.') text else text.replace('.', decimalSeparator)
                }
                else -> throw IllegalArgumentException("unsupported conversion ${match.value}")
            }
        }
    }
}
