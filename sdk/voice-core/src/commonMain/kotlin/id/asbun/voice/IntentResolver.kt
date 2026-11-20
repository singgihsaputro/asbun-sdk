package id.asbun.voice

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull

public enum class ResolutionSource { KEYWORD, LLM }

/** Outcome of resolving one utterance. */
public sealed interface Resolution {
    public val heard: String

    /** A command with every required value; ready to confirm or dispatch. */
    public data class Matched(
        override val heard: String,
        val command: Command,
        val params: Map<String, String>,
        val source: ResolutionSource,
        val confidence: Double,
    ) : Resolution

    /** A command was recognised but a required value is missing: ask [question]. */
    public data class NeedsInput(
        override val heard: String,
        val command: Command,
        val params: Map<String, String>,
        val missing: CommandParam,
        val question: String,
        val source: ResolutionSource,
        val confidence: Double,
    ) : Resolution

    /** Nothing matched. [question] is the LLM's clarifying question, if it asked one. */
    public data class NoMatch(
        override val heard: String,
        val question: String? = null,
        val error: LlmException? = null,
    ) : Resolution
}

/**
 * Keyword matching first (on device), the LLM only when [ResolutionConfig.mode] allows and
 * keywords score below [ResolutionConfig.keywordMinScore]. Every LLM answer is validated:
 * the command must exist and pass `when`, enum values must be known, values filled from
 * context never come from the LLM, and confidence must reach [ResolutionConfig.llmMinConfidence].
 */
public class IntentResolver(
    private val manifest: CommandManifest,
    private val config: VoiceConfig,
    private val llm: LlmClient?,
) {
    public val matcher: KeywordMatcher = KeywordMatcher(manifest, config.locale)

    public suspend fun resolve(alternatives: List<String>, context: VoiceContext): Resolution {
        val heard = alternatives.firstOrNull { it.isNotBlank() }?.trim() ?: return Resolution.NoMatch("")
        val mode = config.resolution.mode
        val keyword = if (mode == ResolutionMode.LLM_ONLY) null else bestKeyword(alternatives, context)
        val keywordOk = keyword != null && keyword.score >= config.resolution.keywordMinScore
        val llmAllowed = llm != null && mode != ResolutionMode.KEYWORD_ONLY

        if (mode == ResolutionMode.KEYWORD_FIRST || mode == ResolutionMode.KEYWORD_ONLY) {
            if (keywordOk) return fromKeyword(heard, keyword, context)
            if (!llmAllowed) return Resolution.NoMatch(heard)
            return askLlm(heard, context)
        }
        // LLM_FIRST / LLM_ONLY
        if (llmAllowed) {
            val viaLlm = askLlm(heard, context)
            if (viaLlm !is Resolution.NoMatch) return viaLlm
            if (mode == ResolutionMode.LLM_FIRST && keywordOk) return fromKeyword(heard, keyword, context)
            return viaLlm
        }
        return if (keywordOk) fromKeyword(heard, keyword, context) else Resolution.NoMatch(heard)
    }

    /** Adds context values and defaults; returns [Resolution.NeedsInput] for the first missing required value. */
    public fun complete(
        heard: String,
        command: Command,
        speechParams: Map<String, String>,
        context: VoiceContext,
        source: ResolutionSource,
        confidence: Double,
    ): Resolution {
        val params = speechParams.toMutableMap()
        for (p in command.params) {
            if (params[p.name].isNullOrBlank() && p.from != ParamSource.SPEECH) {
                context[p.name]?.toString()?.takeIf { it.isNotBlank() }?.let { params[p.name] = it }
            }
            if (params[p.name].isNullOrBlank() && p.default != null) {
                Deeplinks.render(p.default, params, context).takeIf { it.isNotBlank() }?.let { params[p.name] = it }
            }
        }
        val missing = command.params.firstOrNull { it.required && params[it.name].isNullOrBlank() }
        if (missing != null) {
            val question = missing.ask?.resolve(config.locale, manifest.defaultLocale)?.let { Deeplinks.render(it, params, context) }
                ?: Deeplinks.render(config.messages.reprompt, params, context)
            return Resolution.NeedsInput(heard, command, params, missing, question, source, confidence)
        }
        return Resolution.Matched(heard, command, params, source, confidence)
    }

    private fun bestKeyword(alternatives: List<String>, context: VoiceContext): KeywordMatch? =
        alternatives.filter { it.isNotBlank() }
            .mapNotNull { matcher.match(it, context) }
            .maxByOrNull { it.score }

    private fun fromKeyword(heard: String, match: KeywordMatch, context: VoiceContext): Resolution =
        complete(heard, match.command, match.params, context, ResolutionSource.KEYWORD, match.score)

    private suspend fun askLlm(heard: String, context: VoiceContext): Resolution {
        val client = llm ?: return Resolution.NoMatch(heard)
        val allowed = manifest.commands.filter { Conditions.matches(it.conditions, context) }
        if (allowed.isEmpty()) return Resolution.NoMatch(heard)
        val tools = LlmTools.build(allowed)
        val llmContext = if (config.privacy.sendContextToLlm) context.toJson() else JsonObject(emptyMap())
        val transcript = config.privacy.redactor?.invoke(heard) ?: heard
        val decision = try {
            client.decide(
                LlmRequest(
                    transcript = transcript,
                    locale = config.locale,
                    context = llmContext,
                    tools = tools,
                    systemPrompt = LlmTools.systemPrompt(config.locale),
                ),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: LlmException) {
            return Resolution.NoMatch(heard, error = e)
        } catch (e: Exception) {
            return Resolution.NoMatch(heard, error = LlmException(VoiceErrorCode.LLM_INVALID_RESPONSE, e.message ?: "LLM error", e))
        }
        return validate(heard, decision, tools, allowed, context)
    }

    private fun validate(
        heard: String,
        decision: LlmDecision,
        tools: List<LlmTool>,
        allowed: List<Command>,
        context: VoiceContext,
    ): Resolution {
        if (decision.refused || decision.toolName == null) return Resolution.NoMatch(heard, decision.question)
        val tool = tools.firstOrNull { it.name == decision.toolName } ?: return Resolution.NoMatch(heard, decision.question)
        val command = allowed.firstOrNull { it.id == tool.commandId } ?: return Resolution.NoMatch(heard, decision.question)
        val confidence = decision.confidence ?: 1.0
        if (confidence < config.resolution.llmMinConfidence) return Resolution.NoMatch(heard, decision.question)

        val params = mutableMapOf<String, String>()
        for (p in command.params) {
            if (p.from == ParamSource.CONTEXT) continue // ids never come from the LLM
            val raw = decision.arguments[p.name]?.let { it as? JsonPrimitive } ?: continue
            val value = when (p.type) {
                ParamType.ENUM -> {
                    val text = raw.contentOrNull ?: continue
                    p.values.keys.firstOrNull { it.equals(text, ignoreCase = true) }
                        ?: matcher.enumValueIn(text, p)
                        ?: continue
                }
                ParamType.NUMBER -> raw.doubleOrNull?.let { if (it % 1.0 == 0.0) it.toLong().toString() else it.toString() } ?: continue
                ParamType.TEXT, ParamType.DATE -> raw.contentOrNull?.trim()?.takeIf { it.isNotEmpty() } ?: continue
            }
            params[p.name] = value
        }
        return complete(heard, command, params, context, ResolutionSource.LLM, confidence)
    }
}
