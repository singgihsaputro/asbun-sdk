package id.asbun.voice

import kotlin.math.max
import kotlin.math.min

/** One word of an utterance: as spoken (for text values) and in matching form. */
internal data class Token(val original: String, val canon: String)

/** Lowercase, drop punctuation, apply aliases, strip known suffixes, trim fillers. */
internal class Normalizer(
    private val aliases: Map<String, String>,
    private val fillers: Set<String>,
    private val suffixes: List<String>,
    knownWords: Set<String>,
) {
    private val known = knownWords + aliases.values

    fun tokenize(text: String): List<Token> =
        text.trim().split(Regex("\\s+")).mapNotNull { piece ->
            val basic = piece.lowercase().filter { it.isLetterOrDigit() }
            if (basic.isEmpty()) null else Token(piece, canonical(basic))
        }

    fun canonical(word: String): String {
        aliases[word]?.let { return it }
        if (word in known) return word
        for (suffix in suffixes) {
            if (word.length > suffix.length + 2 && word.endsWith(suffix)) {
                val root = word.dropLast(suffix.length)
                val mapped = aliases[root] ?: root
                if (mapped in known) return mapped
            }
        }
        return word
    }

    /** Canonical words of a phrase or synonym (no filler trimming). */
    fun words(text: String): List<String> = tokenize(text).map { it.canon }

    /** Index window [start, end) left after trimming fillers; never trims to nothing. */
    fun window(tokens: List<Token>): IntRange {
        var start = 0
        var end = tokens.size
        while (end - start > 1 && tokens[start].canon in fillers) start++
        while (end - start > 1 && tokens[end - 1].canon in fillers) end--
        return start until end
    }

    fun isFiller(canon: String): Boolean = canon in fillers
}

/** Best keyword match for an utterance. */
public data class KeywordMatch(
    val command: Command,
    /** Values from speech: enum keys or original words. Context values are added later. */
    val params: Map<String, String>,
    val score: Double,
    val phrase: String,
)

/**
 * On-device matcher, generalized from the Ojol Mitra prototype's parser:
 * 1. normalize (aliases, suffixes, fillers), 2. drop commands whose `when` fails or whose
 * `notPhrases` occur, 3. match phrases with `{slots}`, 4. extract other speech parameters
 * (enum synonyms anywhere, text after `after` words), 5. score by how much of the utterance
 * the match explains.
 *
 * Score = base x coverage - 0.1 per one-letter typo, where base is 1.0 for a phrase made only
 * of words and 0.9 when slots or extracted parameters are involved.
 */
public class KeywordMatcher(
    private val manifest: CommandManifest,
    private val locale: String,
) {
    private sealed interface Element {
        data class Word(val word: String) : Element
        data class Slot(val param: CommandParam) : Element
    }

    private class CompiledPhrase(val source: String, val elements: List<Element>) {
        val literalCount = elements.count { it is Element.Word }
        val hasSlots = elements.any { it is Element.Slot }
    }

    private class CompiledCommand(
        val command: Command,
        val phrases: List<CompiledPhrase>,
        /** Word-only phrases, longest first: repeats of the command ("gas, terima") count as covered. */
        val literalPhrases: List<List<String>>,
        val notPhrases: List<List<String>>,
        val synonyms: Map<String, List<Pair<String, List<String>>>>,
        val after: Map<String, Set<String>>,
        /** Enum synonyms (word lists): a text value after an `after` word ends where one starts. */
        val stops: List<List<String>>,
    )

    private val normalizer: Normalizer
    private val compiled: List<CompiledCommand>

    init {
        val rawWords = mutableSetOf<String>()
        manifest.commands.forEach { command ->
            command.phrases.allValues.flatten().forEach { rawWords += plainWords(it) }
            command.params.forEach { p -> p.values.values.flatten().forEach { rawWords += plainWords(it) } }
        }
        normalizer = Normalizer(
            aliases = manifest.aliasesFor(locale),
            fillers = manifest.fillersFor(locale),
            suffixes = manifest.suffixesFor(locale),
            knownWords = rawWords,
        )
        compiled = manifest.commands.map { compile(it) }
    }

    /** All commands that match, best first. */
    public fun matchAll(text: String, context: VoiceContext): List<KeywordMatch> {
        val tokens = normalizer.tokenize(text)
        if (tokens.isEmpty()) return emptyList()
        val window = normalizer.window(tokens)
        val canon = tokens.map { it.canon }
        val results = mutableListOf<Pair<KeywordMatch, Int>>()
        for (cc in compiled) {
            if (!Conditions.matches(cc.command.conditions, context)) continue
            if (cc.notPhrases.any { containsSequence(canon, it) }) continue
            var best: Pair<KeywordMatch, Int>? = null
            for (phrase in cc.phrases) {
                val m = matchPhrase(cc, phrase, tokens, window) ?: continue
                val candidate = m to phrase.literalCount
                if (best == null || isBetter(candidate, best)) best = candidate
            }
            best?.let { results += it }
        }
        return results
            .sortedWith(
                compareByDescending<Pair<KeywordMatch, Int>> { it.first.score }
                    .thenByDescending { it.first.command.priority }
                    .thenByDescending { it.second },
            )
            .map { it.first }
    }

    public fun match(text: String, context: VoiceContext): KeywordMatch? = matchAll(text, context).firstOrNull()

    /** Finds an enum value key in free text, e.g. the answer to "Mau lapor apa?". */
    public fun enumValueIn(text: String, param: CommandParam): String? {
        val canon = normalizer.tokenize(text).map { it.canon }
        val synonyms = enumSynonyms(param)
        for (i in canon.indices) {
            for ((key, words) in synonyms) {
                if (startsWithAt(canon, i, words)) return key
            }
        }
        return null
    }

    /**
     * Wake-word check: null unless a phrase (each word exact, or one letter off when 5+ letters) starts [text];
     * otherwise the words after it as spoken, "" when nothing follows. Fillers are kept on purpose.
     */
    internal fun wakeRemainder(text: String, phrases: List<String>): String? {
        val tokens = normalizer.tokenize(text)
        for (phrase in phrases) {
            val words = normalizer.words(phrase)
            if (words.isEmpty()) continue
            val used = when {
                tokens.size >= words.size &&
                    words.indices.all { tokens[it].canon == words[it] || (words[it].length >= 5 && withinOneEdit(tokens[it].canon, words[it])) } ->
                    words.size
                else -> mergedPhraseLength(tokens, words)
            } ?: continue
            return tokens.drop(used).joinToString(" ") { it.original }.trimStart(',', '.', '!', '?', ' ')
        }
        return null
    }

    /**
     * The recognizer sometimes writes a multi-word phrase as fewer words ("alomitra" for "halo mitra").
     * Compares the first 1..n-1 tokens, spaces removed, with the phrase spaces removed: 1 letter off is
     * allowed, 2 when the phrase has 8+ letters. Returns how many tokens the phrase used, or null.
     * Tokens that keep the phrase's word count go through the per-word rule only, so real words that
     * sound close ("kalau mitra", "alam mitra") are not widened by this.
     */
    private fun mergedPhraseLength(tokens: List<Token>, words: List<String>): Int? {
        val target = words.joinToString("")
        val allowed = if (target.length >= 8) 2 else 1
        for (n in 1 until minOf(words.size, tokens.size + 1)) {
            val joined = tokens.take(n).joinToString("") { it.canon }
            if (editDistance(joined, target, allowed) <= allowed) return n
        }
        return null
    }

    /** Matching form of a word list, for yes/no/cancel checks. */
    public fun canonicalWords(text: String): List<String> = normalizer.tokenize(text).map { it.canon }

    /** First synonym of an enum value, for speaking it back. */
    public fun displayValue(param: CommandParam, value: String): String =
        if (param.type == ParamType.ENUM) param.values[value]?.firstOrNull() ?: value else value

    // ------------------------------------------------------------------ compile

    private fun compile(command: Command): CompiledCommand {
        val phrases = (command.phrases.resolve(locale, manifest.defaultLocale) ?: emptyList()).map { compilePhrase(it, command) }
        val notPhrases = (command.notPhrases?.resolve(locale, manifest.defaultLocale) ?: emptyList()).map { normalizer.words(it) }
        val synonyms = command.params.filter { it.type == ParamType.ENUM }.associate { it.name to enumSynonyms(it) }
        val after = command.params.associate { p ->
            p.name to (p.after?.resolve(locale, manifest.defaultLocale) ?: emptyList()).map { normalizer.canonical(it.lowercase()) }.toSet()
        }
        val stops = synonyms.values.flatten().map { it.second }.distinct()
        val literalPhrases = phrases.filterNot { it.hasSlots }
            .map { p -> p.elements.map { (it as Element.Word).word } }
            .filter { it.isNotEmpty() }
            .sortedByDescending { it.size }
        return CompiledCommand(command, phrases, literalPhrases, notPhrases, synonyms, after, stops)
    }

    private fun compilePhrase(phrase: String, command: Command): CompiledPhrase {
        val elements = mutableListOf<Element>()
        phrase.trim().split(Regex("\\s+")).forEach { piece ->
            val slot = SLOT.matchEntire(piece)
            if (slot != null) {
                elements += Element.Slot(command.param(slot.groupValues[1])!!)
            } else {
                normalizer.words(piece).forEach { elements += Element.Word(it) }
            }
        }
        return CompiledPhrase(phrase, elements)
    }

    private fun enumSynonyms(param: CommandParam): List<Pair<String, List<String>>> =
        param.values.flatMap { (key, words) -> words.map { key to normalizer.words(it) } }
            .filter { it.second.isNotEmpty() }
            .sortedByDescending { it.second.size }

    // ------------------------------------------------------------------ match

    private class Partial(val end: Int, val typos: Int, val values: Map<String, IntRange>, val enumValues: Map<String, String>)

    private fun matchPhrase(cc: CompiledCommand, phrase: CompiledPhrase, tokens: List<Token>, window: IntRange): KeywordMatch? {
        val canon = tokens.map { it.canon }
        val first = window.first
        val last = window.last + 1
        var best: Partial? = null
        var bestStart = 0
        for (start in first until max(first + 1, last)) {
            val p = matchFrom(cc, phrase.elements, 0, canon, start, last) ?: continue
            val current = best
            val covered = p.end - start
            val bestCovered = if (current == null) -1 else current.end - bestStart
            if (current == null || covered > bestCovered || (covered == bestCovered && p.typos < current.typos)) {
                best = p
                bestStart = start
            }
        }
        val partial = best ?: return null
        val start = bestStart
        // A phrase must explain at least one word: "{type}" alone may not match an empty slot.
        if (partial.end == start) return null

        val covered = BooleanArray(canon.size)
        for (i in start until partial.end) covered[i] = true
        // Saying the command twice, or two ways, is still one command: "gas, terima!", "macet padat".
        cc.literalPhrases.forEach { markAll(canon, covered, it, first, last) }
        for ((name, key) in partial.enumValues) {
            cc.synonyms[name].orEmpty().filter { it.first == key }.forEach { markAll(canon, covered, it.second, first, last) }
        }
        val values = mutableMapOf<String, String>()
        partial.enumValues.forEach { (k, v) -> values[k] = v }
        partial.values.forEach { (name, range) ->
            // A trailing text slot keeps trailing filler words ("... ya pak") in its value.
            val end = if (range.last + 1 == last && range.last >= range.first) tokens.size else range.last + 1
            if (range.last >= range.first) values[name] = tokens.subList(range.first, end).joinToString(" ") { it.original }
        }
        var extracted = false
        for (param in cc.command.params) {
            if (param.from == ParamSource.CONTEXT || values.containsKey(param.name)) continue
            if (param.type == ParamType.ENUM) {
                val syn = cc.synonyms[param.name] ?: continue
                var found: String? = null
                var i = first
                while (i < last) {
                    if (!covered[i]) {
                        val hit = syn.firstOrNull { (_, words) -> fitsUncovered(canon, covered, i, words, last) }
                        if (hit != null) {
                            if (found == null) found = hit.first
                            for (j in i until i + hit.second.size) covered[j] = true
                            i += hit.second.size
                            continue
                        }
                    }
                    i++
                }
                if (found != null) {
                    values[param.name] = found
                    extracted = true
                }
            } else if (param.type == ParamType.TEXT || param.type == ParamType.DATE) {
                val afterWords = cc.after[param.name].orEmpty()
                if (afterWords.isEmpty()) continue
                val at = (first until last).firstOrNull { !covered[it] && canon[it] in afterWords } ?: continue
                var end = at + 1
                while (end < last && !covered[end] && cc.stops.none { fitsUncovered(canon, covered, end, it, last) }) end++
                if (end > at + 1) {
                    values[param.name] = tokens.subList(at + 1, end).joinToString(" ") { it.original }
                    for (j in at until end) covered[j] = true
                    extracted = true
                }
            }
        }
        val size = last - first
        val coveredCount = (first until last).count { covered[it] }
        val base = if (phrase.hasSlots || extracted) 0.9 else 1.0
        val score = (base * coveredCount / size - 0.1 * partial.typos).coerceIn(0.0, 1.0)
        return KeywordMatch(cc.command, values, score, phrase.source)
    }

    private fun matchFrom(cc: CompiledCommand, elements: List<Element>, ei: Int, canon: List<String>, ti: Int, end: Int): Partial? {
        if (ei == elements.size) return Partial(ti, 0, emptyMap(), emptyMap())
        return when (val el = elements[ei]) {
            is Element.Word -> {
                if (ti >= end) return null
                val typo = when {
                    canon[ti] == el.word -> 0
                    el.word.length >= 5 && withinOneEdit(canon[ti], el.word) -> 1
                    else -> return null
                }
                matchFrom(cc, elements, ei + 1, canon, ti + 1, end)?.let { Partial(it.end, it.typos + typo, it.values, it.enumValues) }
            }
            is Element.Slot -> {
                val param = el.param
                val isLast = ei == elements.lastIndex
                when (param.type) {
                    ParamType.ENUM -> {
                        if (ti >= end) return if (isLast) Partial(ti, 0, emptyMap(), emptyMap()) else null
                        for ((key, words) in cc.synonyms[param.name].orEmpty()) {
                            if (ti + words.size <= end && startsWithAt(canon, ti, words)) {
                                val rest = matchFrom(cc, elements, ei + 1, canon, ti + words.size, end) ?: continue
                                return Partial(rest.end, rest.typos, rest.values, rest.enumValues + (param.name to key))
                            }
                        }
                        // A trailing slot may be left empty: the value is asked for afterwards.
                        if (isLast) Partial(ti, 0, emptyMap(), emptyMap()) else null
                    }
                    ParamType.NUMBER -> {
                        if (ti < end && canon[ti].all { it.isDigit() }) {
                            matchFrom(cc, elements, ei + 1, canon, ti + 1, end)?.let {
                                Partial(it.end, it.typos, it.values + (param.name to ti..ti), it.enumValues)
                            }
                        } else if (isLast) Partial(ti, 0, emptyMap(), emptyMap()) else null
                    }
                    ParamType.TEXT, ParamType.DATE -> {
                        if (isLast) {
                            Partial(end, 0, mapOf(param.name to (ti until end)), emptyMap())
                        } else {
                            for (k in 1..(end - ti)) {
                                val rest = matchFrom(cc, elements, ei + 1, canon, ti + k, end) ?: continue
                                return Partial(rest.end, rest.typos, rest.values + (param.name to (ti until ti + k)), rest.enumValues)
                            }
                            null
                        }
                    }
                }
            }
        }
    }

    private fun isBetter(a: Pair<KeywordMatch, Int>, b: Pair<KeywordMatch, Int>): Boolean =
        a.first.score > b.first.score || (a.first.score == b.first.score && a.second > b.second)

    private fun markAll(canon: List<String>, covered: BooleanArray, words: List<String>, first: Int, last: Int) {
        var i = first
        while (i < last) {
            if (fitsUncovered(canon, covered, i, words, last)) {
                for (j in i until i + words.size) covered[j] = true
                i += words.size
            } else {
                i++
            }
        }
    }

    private fun fitsUncovered(canon: List<String>, covered: BooleanArray, at: Int, words: List<String>, end: Int): Boolean {
        if (at + words.size > end) return false
        for (j in words.indices) if (covered[at + j] || canon[at + j] != words[j]) return false
        return true
    }

    private companion object {
        val SLOT = Regex("\\{([A-Za-z0-9_]+)\\}[.,!?]*")

        fun plainWords(text: String): List<String> =
            text.split(Regex("\\s+")).filterNot { SLOT.matches(it) }.map { w -> w.lowercase().filter { it.isLetterOrDigit() } }.filter { it.isNotEmpty() }

        fun startsWithAt(tokens: List<String>, at: Int, words: List<String>): Boolean =
            at + words.size <= tokens.size && words.indices.all { tokens[at + it] == words[it] }

        fun containsSequence(tokens: List<String>, words: List<String>): Boolean =
            words.isNotEmpty() && (0..tokens.size - words.size).any { startsWithAt(tokens, it, words) }

        /** Levenshtein distance, or [limit] + 1 as soon as it is known to exceed [limit]. */
        fun editDistance(a: String, b: String, limit: Int): Int {
            if (kotlin.math.abs(a.length - b.length) > limit) return limit + 1
            var prev = IntArray(b.length + 1) { it }
            for (i in 1..a.length) {
                val cur = IntArray(b.length + 1)
                cur[0] = i
                for (j in 1..b.length) {
                    cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
                }
                if (cur.min() > limit) return limit + 1
                prev = cur
            }
            return prev[b.length]
        }

        /** Levenshtein distance <= 1. */
        fun withinOneEdit(a: String, b: String): Boolean {
            if (kotlin.math.abs(a.length - b.length) > 1) return false
            var i = 0
            var j = 0
            var edits = 0
            while (i < a.length && j < b.length) {
                if (a[i] == b[j]) {
                    i++; j++
                } else {
                    if (++edits > 1) return false
                    when {
                        a.length > b.length -> i++
                        a.length < b.length -> j++
                        else -> { i++; j++ }
                    }
                }
            }
            edits += (a.length - i) + (b.length - j)
            return edits <= 1 && min(a.length, b.length) > 0
        }
    }
}
