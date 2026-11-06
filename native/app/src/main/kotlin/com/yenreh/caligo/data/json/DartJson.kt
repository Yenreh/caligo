package com.yenreh.caligo.data.json

import java.math.BigDecimal
import kotlin.math.abs
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/*
 * JSON read the way the Flutter app read it: `json.decode` and Dart's casts.
 * The app's files and the services' answers are taken or refused exactly
 * as before, so a backup or a cache the Flutter app wrote reads the same.
 */

/** Text that is not JSON, as Dart's FormatException from `json.decode` */
class JsonFormatException(message: String, cause: Throwable? = null) :
    RuntimeException(message, cause)

/** A JSON value of the wrong type, as Dart's TypeError from a failed cast */
class JsonTypeException(message: String) : RuntimeException(message)

private val number = Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")
private val integer = Regex("-?[0-9]+")

/**
 * Parse [text] as strict JSON. kotlinx takes bare words as values even
 * when not lenient, so every literal is checked to be one JSON allows.
 */
fun parseJson(text: String): JsonElement {
    val element = try {
        Json.parseToJsonElement(text)
    } catch (e: SerializationException) {
        throw JsonFormatException(e.message ?: "Invalid JSON", e)
    }
    val pending = ArrayDeque<JsonElement>().apply { add(element) }
    while (pending.isNotEmpty()) {
        when (val next = pending.removeLast()) {
            is JsonObject -> pending.addAll(next.values)
            is JsonArray -> pending.addAll(next)
            is JsonNull -> Unit
            is JsonPrimitive -> if (!next.isString &&
                next.content != "true" &&
                next.content != "false" &&
                !number.matches(next.content)
            ) {
                throw JsonFormatException("Unexpected literal: ${next.content}")
            }
        }
    }
    return element
}

private fun JsonElement?.typeError(expected: String): Nothing =
    throw JsonTypeException("Expected $expected, found ${this ?: "nothing"}")

private val JsonElement?.isNull get() = this == null || this is JsonNull

/** `value is num` */
val JsonElement?.isNum: Boolean
    get() = this is JsonPrimitive && this !is JsonNull && !isString &&
        content != "true" && content != "false"

/** Dart decodes a number without fraction or exponent that fits 64 bits as an int */
private val JsonElement?.isInt: Boolean
    get() = isNum && integer.matches((this as JsonPrimitive).content) &&
        content.toLongOrNull() != null

/** `value as String?` */
fun JsonElement?.stringOrNull(): String? = when {
    isNull -> null
    this is JsonPrimitive && isString -> content
    else -> typeError("a string")
}

/** `value as String` */
fun JsonElement?.string(): String = stringOrNull() ?: typeError("a string")

/** `value as bool?` */
fun JsonElement?.boolOrNull(): Boolean? = when {
    isNull -> null
    this is JsonPrimitive && !isString && content == "true" -> true
    this is JsonPrimitive && !isString && content == "false" -> false
    else -> typeError("a bool")
}

/** `value as int?`; an int past 32 bits is refused too */
fun JsonElement?.intOrNull(): Int? = when {
    isNull -> null
    isInt -> (this as JsonPrimitive).content.toLong().let {
        if (it in Int.MIN_VALUE..Int.MAX_VALUE) it.toInt() else typeError("a 32-bit int")
    }
    else -> typeError("an int")
}

/** `(value as num?)?.toDouble()` */
fun JsonElement?.doubleOrNull(): Double? = when {
    isNull -> null
    isNum -> (this as JsonPrimitive).content.toDouble()
    else -> typeError("a number")
}

/** `(value as num).toDouble()` */
fun JsonElement?.double(): Double = doubleOrNull() ?: typeError("a number")

/** `(value as num?)?.toInt()`, which truncates a double */
fun JsonElement?.longOrNull(): Long? = when {
    isNull -> null
    isInt -> (this as JsonPrimitive).content.toLong()
    isNum -> (this as JsonPrimitive).content.toDouble().toLong()
    else -> typeError("a number")
}

/** `(value as num).toInt()` */
fun JsonElement?.long(): Long = longOrNull() ?: typeError("a number")

/** `value as List?` */
fun JsonElement?.listOrNull(): JsonArray? = when {
    isNull -> null
    this is JsonArray -> this
    else -> typeError("a list")
}

/** `value as Map<String, dynamic>` */
fun JsonElement?.obj(): JsonObject = this as? JsonObject ?: typeError("a map")

/** `value?.toString()`, as Dart prints the decoded value */
fun JsonElement?.dartString(): String? = if (isNull) null else dartPrint(this!!)

private fun dartPrint(element: JsonElement): String = when (element) {
    is JsonNull -> "null"
    is JsonObject -> element.entries.joinToString(", ", "{", "}") { (key, value) ->
        "$key: ${dartPrint(value)}"
    }
    is JsonArray -> element.joinToString(", ", "[", "]") { dartPrint(it) }
    is JsonPrimitive -> when {
        element.isString || !element.isNum -> element.content
        element.isInt -> element.content.toLong().toString()
        else -> dartDouble(element.content.toDouble())
    }
}

/**
 * [value] as Dart's `double.toString` writes it: the shortest digits that
 * read back the same, in decimals from 1e-6 up to 1e21 and with ".0" on a
 * whole number, in exponent form outside that range.
 */
fun dartDouble(value: Double): String {
    if (value.isNaN()) return "NaN"
    if (value.isInfinite()) return if (value > 0) "Infinity" else "-Infinity"
    if (value == 0.0) return if (1 / value < 0) "-0.0" else "0.0"
    // Java writes the shortest digits too, only spread differently
    val shortest = BigDecimal(value.toString()).stripTrailingZeros()
    if (abs(value) >= 1e-6 && abs(value) < 1e21) {
        val plain = shortest.toPlainString()
        return if ('.' in plain) plain else "$plain.0"
    }
    val digits = shortest.unscaledValue().abs().toString()
    val exponent = digits.length - 1 - shortest.scale()
    val mantissa = if (digits.length == 1) digits else "${digits[0]}.${digits.substring(1)}"
    val sign = if (value < 0) "-" else ""
    return "$sign${mantissa}e${if (exponent >= 0) "+" else "-"}${abs(exponent)}"
}
