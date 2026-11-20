package id.asbun.voice

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

/**
 * What the app currently shows. Used to filter commands (`when`), fill `from: context`
 * parameters and `{context.key}` placeholders, and (unless disabled) sent to the LLM.
 *
 * Values must be strings, numbers or booleans, at most [MAX_SIZE_CHARS] characters as JSON.
 */
public data class VoiceContext(
    val screen: String? = null,
    val values: Map<String, Any?> = emptyMap(),
) {
    init {
        for ((key, value) in values) {
            require(value == null || value is String || value is Number || value is Boolean) {
                "Context value '$key' must be a string, number or boolean"
            }
        }
        require(toJson().toString().length <= MAX_SIZE_CHARS) { "VoiceContext is larger than $MAX_SIZE_CHARS characters" }
    }

    /** `screen` or a value. */
    public operator fun get(key: String): Any? = if (key == "screen") screen else values[key]

    public fun toJson(): JsonObject = JsonObject(
        buildMap {
            screen?.let { put("screen", JsonPrimitive(it)) }
            for ((key, value) in this@VoiceContext.values) {
                put(
                    key,
                    when (value) {
                        null -> JsonNull
                        is String -> JsonPrimitive(value)
                        is Number -> JsonPrimitive(value)
                        is Boolean -> JsonPrimitive(value)
                        else -> JsonPrimitive(value.toString())
                    },
                )
            }
        },
    )

    public companion object {
        public const val MAX_SIZE_CHARS: Int = 2_048
        public val EMPTY: VoiceContext = VoiceContext()
    }
}

/** Evaluates a command's `when` conditions; every listed key must match. */
internal object Conditions {
    fun matches(conditions: Map<String, JsonElement>, context: VoiceContext): Boolean =
        conditions.entries.all { entry -> matchesValue(entry.value, context[entry.key]) }

    private fun matchesValue(expected: JsonElement, actual: Any?): Boolean = when (expected) {
        is JsonArray -> expected.any { matchesValue(it, actual) }
        is JsonNull -> actual == null
        is JsonPrimitive -> when {
            actual == null -> false
            expected.isString -> expected.content == actual.toString()
            expected.booleanOrNull != null -> expected.booleanOrNull == (actual as? Boolean ?: actual.toString().toBooleanStrictOrNull())
            expected.doubleOrNull != null -> expected.doubleOrNull == (actual as? Number)?.toDouble()
            else -> false
        }
        is JsonObject -> false
    }
}
