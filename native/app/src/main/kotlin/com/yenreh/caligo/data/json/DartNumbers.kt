package com.yenreh.caligo.data.json

private val dartInt = Regex("^([+-]?)(?:0[xX]([0-9a-fA-F]+)|([0-9]+))$")

/**
 * Dart's `int.tryParse` on the VM: surrounding whitespace ignored, an
 * optional sign, decimal digits or a `0x` hexadecimal literal, null for
 * anything else or past 64 bits
 */
fun dartIntTryParse(text: String): Long? {
    val match = dartInt.matchEntire(text.trim()) ?: return null
    val (sign, hex, decimal) = match.destructured
    return if (hex.isNotEmpty()) {
        // Hexadecimal literals past 2^63 read as unsigned, as in Dart
        val value = hex.toULongOrNull(16)?.toLong() ?: return null
        if (sign == "-") -value else value
    } else {
        "$sign$decimal".toLongOrNull()
    }
}
