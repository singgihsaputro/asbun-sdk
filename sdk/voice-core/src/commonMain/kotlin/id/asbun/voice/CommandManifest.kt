package id.asbun.voice

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

public class ManifestException(message: String) : IllegalArgumentException(message)

/**
 * A value that may differ per language: either one plain value for every locale, or an object
 * keyed by locale (`{"id-ID": ..., "en-US": ...}`).
 */
public class Localized<T>(
    private val byLocale: Map<String, T>,
    private val anyLocale: T?,
) {
    /** Exact locale, then same language, then the value for every locale, then [defaultLocale]. */
    public fun resolve(locale: String, defaultLocale: String): T? {
        byLocale[locale]?.let { return it }
        val language = locale.substringBefore('-').lowercase()
        byLocale.entries.firstOrNull { it.key.substringBefore('-').lowercase() == language }?.let { return it.value }
        anyLocale?.let { return it }
        return byLocale[defaultLocale] ?: byLocale.values.firstOrNull()
    }

    public val isEmpty: Boolean get() = byLocale.isEmpty() && anyLocale == null

    /** Every value, for validation and tool descriptions. */
    public val allValues: List<T> get() = listOfNotNull(anyLocale) + byLocale.values

    public companion object {
        public fun <T> of(value: T): Localized<T> = Localized(emptyMap(), value)
    }
}

public enum class ParamType { TEXT, ENUM, NUMBER, DATE }

public enum class ParamSource { SPEECH, CONTEXT, BOTH }

public data class CommandParam(
    val name: String,
    val type: ParamType,
    val from: ParamSource,
    val required: Boolean,
    /** ENUM only: value key -> spoken synonyms. */
    val values: Map<String, List<String>> = emptyMap(),
    /** May use `{context.key}`. */
    val default: String? = null,
    /** Question asked when a required value is missing. */
    val ask: Localized<String>? = null,
    /** TEXT outside the phrase: words that introduce the value, e.g. `["di", "dekat"]` for a location. */
    val after: Localized<List<String>>? = null,
)

public sealed interface CommandAction {
    public data class Deeplink(val url: String) : CommandAction
    public data class Handler(val name: String) : CommandAction
}

public data class Command(
    val id: String,
    val description: String,
    val phrases: Localized<List<String>>,
    val notPhrases: Localized<List<String>>?,
    /** `when` conditions on the context. */
    val conditions: Map<String, JsonElement>,
    val params: List<CommandParam>,
    val action: CommandAction,
    val confirm: Localized<String>?,
    val reply: Localized<String>?,
    val priority: Int,
) {
    public fun param(name: String): CommandParam? = params.firstOrNull { it.name == name }
}

/**
 * The app's commands as data: phrases, parameters, context conditions and the deeplink or
 * handler each one fires. Load it with [fromJson]; invalid manifests throw [ManifestException].
 */
public data class CommandManifest(
    val version: Int,
    val app: String,
    val defaultLocale: String,
    val aliases: Map<String, Map<String, String>>,
    val fillers: Map<String, Set<String>>,
    val suffixes: Map<String, List<String>>,
    val commands: List<Command>,
) {
    public fun command(id: String): Command? = commands.firstOrNull { it.id == id }

    internal fun aliasesFor(locale: String): Map<String, String> = forLocale(aliases, locale) ?: emptyMap()
    internal fun fillersFor(locale: String): Set<String> = forLocale(fillers, locale) ?: emptySet()
    internal fun suffixesFor(locale: String): List<String> = forLocale(suffixes, locale) ?: emptyList()

    private fun <T> forLocale(map: Map<String, T>, locale: String): T? {
        map[locale]?.let { return it }
        val language = locale.substringBefore('-').lowercase()
        return map.entries.firstOrNull { it.key.substringBefore('-').lowercase() == language }?.value ?: map[defaultLocale]
    }

    public companion object {
        public const val SUPPORTED_VERSION: Int = 1
        private val ID_PATTERN = Regex("[A-Za-z0-9._-]+")
        private val SLOT_PATTERN = Regex("\\{([A-Za-z0-9_]+)}")

        public fun fromJson(json: String): CommandManifest {
            val root = try {
                Json.parseToJsonElement(json).jsonObject
            } catch (e: Exception) {
                throw ManifestException("Manifest is not a JSON object: ${e.message}")
            }
            val version = root.int("version") ?: throw ManifestException("Manifest needs a numeric 'version'")
            if (version < 1 || version > SUPPORTED_VERSION) {
                throw ManifestException("Manifest version $version is not supported (max $SUPPORTED_VERSION)")
            }
            val defaultLocale = root.string("defaultLocale") ?: "id-ID"
            val commandsJson = root["commands"] as? JsonArray ?: throw ManifestException("Manifest needs a 'commands' array")
            val commands = commandsJson.mapIndexed { index, element ->
                val obj = element as? JsonObject ?: throw ManifestException("commands[$index] is not an object")
                parseCommand(obj, index)
            }
            val ids = mutableSetOf<String>()
            commands.forEach { if (!ids.add(it.id)) throw ManifestException("Duplicate command id '${it.id}'") }

            return CommandManifest(
                version = version,
                app = root.string("app") ?: "",
                defaultLocale = defaultLocale,
                aliases = localeMap(root["aliases"], "aliases") { key, value ->
                    (value as? JsonObject ?: throw ManifestException("aliases.$key must be an object"))
                        .mapKeys { it.key.lowercase() }
                        .mapValues { (it.value as? JsonPrimitive)?.contentOrNull?.lowercase() ?: throw ManifestException("aliases.$key.${it.key} must be a string") }
                },
                fillers = localeMap(root["fillers"], "fillers") { key, value -> stringArray(value, "fillers.$key").map { it.lowercase() }.toSet() },
                suffixes = localeMap(root["suffixes"], "suffixes") { key, value -> stringArray(value, "suffixes.$key").map { it.lowercase() } },
                commands = commands,
            )
        }

        private fun parseCommand(obj: JsonObject, index: Int): Command {
            val id = obj.string("id") ?: throw ManifestException("commands[$index] needs an 'id'")
            if (!ID_PATTERN.matches(id)) throw ManifestException("Command id '$id' may only use letters, digits, '.', '_' and '-'")
            val where = "command '$id'"
            val description = obj.string("description")?.takeIf { it.isNotBlank() }
                ?: throw ManifestException("$where needs a 'description'")
            val phrases = localizedList(obj["phrases"], "$where phrases")
                ?: throw ManifestException("$where needs 'phrases'")
            val params = (obj["params"] as? JsonArray)?.mapIndexed { i, p ->
                parseParam(p as? JsonObject ?: throw ManifestException("$where params[$i] is not an object"), "$where params[$i]")
            } ?: emptyList()
            val names = mutableSetOf<String>()
            params.forEach { if (!names.add(it.name)) throw ManifestException("$where has two params named '${it.name}'") }

            val allPhrases = phrases.allValues.flatten()
            if (allPhrases.isEmpty()) throw ManifestException("$where needs at least one phrase")
            allPhrases.forEach { phrase ->
                SLOT_PATTERN.findAll(phrase).forEach { m ->
                    val slot = m.groupValues[1]
                    val param = params.firstOrNull { it.name == slot }
                        ?: throw ManifestException("$where phrase \"$phrase\" uses unknown slot {$slot}")
                    if (param.from == ParamSource.CONTEXT) throw ManifestException("$where slot {$slot} is filled from context and cannot appear in a phrase")
                }
            }

            val actionObj = obj["action"] as? JsonObject ?: throw ManifestException("$where needs an 'action'")
            val action = when (actionObj.string("type")) {
                "deeplink" -> CommandAction.Deeplink(actionObj.string("url")?.takeIf { it.isNotBlank() } ?: throw ManifestException("$where deeplink needs a 'url'"))
                "handler" -> CommandAction.Handler(actionObj.string("name")?.takeIf { it.isNotBlank() } ?: throw ManifestException("$where handler needs a 'name'"))
                else -> throw ManifestException("$where action type must be 'deeplink' or 'handler'")
            }
            val conditions = when (val w = obj["when"]) {
                null -> emptyMap()
                is JsonObject -> w
                else -> throw ManifestException("$where 'when' must be an object")
            }
            return Command(
                id = id,
                description = description,
                phrases = phrases,
                notPhrases = localizedList(obj["notPhrases"], "$where notPhrases"),
                conditions = conditions,
                params = params,
                action = action,
                confirm = localizedString(obj["confirm"], "$where confirm"),
                reply = localizedString(obj["reply"], "$where reply"),
                priority = obj.int("priority") ?: 0,
            )
        }

        private fun parseParam(obj: JsonObject, where: String): CommandParam {
            val name = obj.string("name") ?: throw ManifestException("$where needs a 'name'")
            val type = when (obj.string("type") ?: "text") {
                "text" -> ParamType.TEXT
                "enum" -> ParamType.ENUM
                "number" -> ParamType.NUMBER
                "date" -> ParamType.DATE
                else -> throw ManifestException("$where type must be text, enum, number or date")
            }
            val from = when (obj.string("from") ?: "speech") {
                "speech" -> ParamSource.SPEECH
                "context" -> ParamSource.CONTEXT
                "both" -> ParamSource.BOTH
                else -> throw ManifestException("$where 'from' must be speech, context or both")
            }
            val values = (obj["values"] as? JsonObject)?.mapValues { (key, v) -> stringArray(v, "$where values.$key") } ?: emptyMap()
            if (type == ParamType.ENUM && values.isEmpty()) throw ManifestException("$where is an enum and needs 'values'")
            return CommandParam(
                name = name,
                type = type,
                from = from,
                required = (obj["required"] as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull() ?: false,
                values = values,
                default = obj.string("default"),
                ask = localizedString(obj["ask"], "$where ask"),
                after = localizedList(obj["after"], "$where after"),
            )
        }

        private fun <T> localeMap(element: JsonElement?, where: String, value: (String, JsonElement) -> T): Map<String, T> = when (element) {
            null -> emptyMap()
            is JsonObject -> element.mapValues { (key, v) -> value(key, v) }
            else -> throw ManifestException("$where must be an object keyed by locale")
        }

        private fun localizedList(element: JsonElement?, where: String): Localized<List<String>>? = when (element) {
            null -> null
            is JsonArray -> Localized.of(stringArray(element, where))
            is JsonObject -> Localized(element.mapValues { (k, v) -> stringArray(v, "$where.$k") }, null)
            else -> throw ManifestException("$where must be a list or an object keyed by locale")
        }

        private fun localizedString(element: JsonElement?, where: String): Localized<String>? = when (element) {
            null -> null
            is JsonPrimitive -> Localized.of(element.contentOrNull ?: throw ManifestException("$where must be text"))
            is JsonObject -> Localized(
                element.mapValues { (k, v) -> (v as? JsonPrimitive)?.contentOrNull ?: throw ManifestException("$where.$k must be text") },
                null,
            )
            else -> throw ManifestException("$where must be text or an object keyed by locale")
        }

        private fun stringArray(element: JsonElement, where: String): List<String> =
            (element as? JsonArray ?: throw ManifestException("$where must be a list"))
                .map { (it as? JsonPrimitive)?.contentOrNull ?: throw ManifestException("$where must only hold text") }
    }
}
