package id.asbun.voice

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ManifestTest {

    @Test
    fun parsesSpecExample() {
        val m = TEST_MANIFEST
        assertEquals(1, m.version)
        assertEquals(5, m.commands.size)
        val accept = assertNotNull(m.command("order.accept"))
        assertEquals(CommandAction.Deeplink("ojolmitra://order/{orderId}/accept"), accept.action)
        assertEquals(ParamSource.CONTEXT, accept.param("orderId")?.from)
        assertEquals(listOf("accept", "take it"), accept.phrases.resolve("en-US", m.defaultLocale))
        assertEquals("Order diterima.", accept.reply?.resolve("id", m.defaultLocale))
        // Unknown locale falls back to the default locale.
        assertEquals("Order diterima.", accept.reply?.resolve("fr-FR", m.defaultLocale))
        // A plain list applies to every locale.
        assertEquals(listOf("bantuan", "help"), m.command("app.help")?.phrases?.resolve("en-US", m.defaultLocale))
    }

    private fun manifestWith(command: String, version: Int = 1) =
        """{ "version": $version, "commands": [ $command ] }"""

    @Test
    fun rejectsInvalidManifests() {
        val ok = """{ "id": "a", "description": "d", "phrases": ["x"], "action": { "type": "handler", "name": "h" } }"""
        CommandManifest.fromJson(manifestWith(ok))

        val cases = mapOf(
            "unsupported version" to manifestWith(ok, version = 2),
            "duplicate id" to """{ "version": 1, "commands": [ $ok, $ok ] }""",
            "unknown slot" to manifestWith("""{ "id": "a", "description": "d", "phrases": ["x {y}"], "action": { "type": "handler", "name": "h" } }"""),
            "context slot in phrase" to manifestWith(
                """{ "id": "a", "description": "d", "phrases": ["x {y}"], "params": [ { "name": "y", "from": "context" } ], "action": { "type": "handler", "name": "h" } }""",
            ),
            "enum without values" to manifestWith(
                """{ "id": "a", "description": "d", "phrases": ["x"], "params": [ { "name": "y", "type": "enum" } ], "action": { "type": "handler", "name": "h" } }""",
            ),
            "missing action" to manifestWith("""{ "id": "a", "description": "d", "phrases": ["x"] }"""),
            "bad action type" to manifestWith("""{ "id": "a", "description": "d", "phrases": ["x"], "action": { "type": "url" } }"""),
            "bad id" to manifestWith("""{ "id": "a b", "description": "d", "phrases": ["x"], "action": { "type": "handler", "name": "h" } }"""),
            "missing description" to manifestWith("""{ "id": "a", "phrases": ["x"], "action": { "type": "handler", "name": "h" } }"""),
            "not json" to "{ nope",
        )
        cases.forEach { (name, json) ->
            assertFailsWith<ManifestException>(name) { CommandManifest.fromJson(json) }
        }
    }
}

class KeywordMatcherTest {
    private val matcher = KeywordMatcher(TEST_MANIFEST, "id-ID")

    private fun match(text: String, context: VoiceContext) = matcher.match(text, context)

    @Test
    fun exactPhraseScoresOne() {
        val m = assertNotNull(match("terima", OFFER))
        assertEquals("order.accept", m.command.id)
        assertEquals(1.0, m.score)
    }

    @Test
    fun fillersAndAliasesAreNormalized() {
        assertEquals("order.accept", match("Halo, terima ya pak", OFFER)?.command?.id)
        assertEquals("order.reject", match("nggak mau", OFFER)?.command?.id) // nggak -> tidak
    }

    @Test
    fun conditionsAndNotPhrasesFilterCommands() {
        assertNull(match("terima", HOME), "order.accept needs screen order_offer")
        assertTrue(match("terima kasih", OFFER)?.command?.id != "order.accept")
    }

    @Test
    fun longerMatchWinsOverPartialOne() {
        val m = assertNotNull(match("jangan ambil", OFFER))
        assertEquals("order.reject", m.command.id)
        assertEquals(1.0, m.score)
        // "ya" explains only a third of the utterance: below the 0.8 threshold.
        val weak = assertNotNull(matcher.matchAll("apa ya tadi", OFFER).firstOrNull())
        assertTrue(weak.score < 0.8, "score was ${weak.score}")
    }

    @Test
    fun trailingTextSlotKeepsOriginalWordsAndFillers() {
        val m = assertNotNull(match("chat Saya sudah di depan ya pak", TRIP))
        assertEquals("chat.send", m.command.id)
        assertEquals("Saya sudah di depan ya pak", m.params["message"])
        assertEquals(0.9, m.score)
        assertEquals("Posisi di mana?", match("bilang ke customer Posisi di mana?", TRIP)?.params?.get("message"))
    }

    @Test
    fun emptyTrailingSlotStillMatchesSoTheValueCanBeAsked() {
        val m = assertNotNull(match("chat", TRIP))
        assertEquals("chat.send", m.command.id)
        assertNull(m.params["message"])
        assertTrue(m.score >= 0.8)
    }

    @Test
    fun enumSlotFreeEnumAndTextAfterWord() {
        val m = assertNotNull(match("lapor macet parah di jalan sudirman", HOME))
        assertEquals("traffic.report", m.command.id)
        assertEquals("MACET", m.params["type"])
        assertEquals("PARAH", m.params["severity"])
        assertEquals("jalan sudirman", m.params["location"])
        assertEquals(0.9, m.score)

        val n = assertNotNull(match("macetnya di sudirman parah banget", HOME)) // suffix -nya, two intensifiers
        assertEquals("MACET", n.params["type"])
        assertEquals("PARAH", n.params["severity"])
        assertEquals("sudirman", n.params["location"])
        assertEquals(0.9, n.score)
    }

    @Test
    fun sayingTheCommandTwiceStillCountsAsOne() {
        val m = assertNotNull(match("gas terima", OFFER))
        assertEquals("order.accept", m.command.id)
        assertEquals(1.0, m.score)
        // Two different commands in one breath stay ambiguous.
        assertTrue(matcher.matchAll("terima tolak", OFFER).all { it.score < 0.8 })
        // A second synonym of the same enum value is explained too.
        val traffic = assertNotNull(match("lapor macet padat", HOME))
        assertEquals("MACET", traffic.params["type"])
        assertEquals(0.9, traffic.score)
    }

    @Test
    fun textAfterWordStopsAtAWholeSynonymOnly() {
        val manifest = CommandManifest.fromJson(
            """
            {"version": 1, "commands": [{
              "id": "traffic.report", "description": "Report traffic.",
              "phrases": ["{type}"],
              "params": [
                {"name": "type", "type": "enum", "values": {"DITUTUP": ["jalan ditutup"], "MACET": ["macet"]}},
                {"name": "location", "type": "text", "after": ["di"]}
              ],
              "action": {"type": "handler", "name": "report"}
            }]}
            """.trimIndent(),
        )
        val m = assertNotNull(KeywordMatcher(manifest, "id-ID").match("jalan ditutup di jalan sudirman macet", VoiceContext.EMPTY))
        assertEquals("DITUTUP", m.params["type"])
        // "jalan" alone is not a synonym, so the place keeps it; "macet" is, so the place ends there.
        assertEquals("jalan sudirman", m.params["location"])
    }

    @Test
    fun oneLetterTypoCostsPointOne() {
        val m = assertNotNull(match("bantuann", HOME))
        assertEquals("app.help", m.command.id)
        assertEquals(0.9, m.score, 1e-9)
    }

    @Test
    fun enumValueInFreeText() {
        val traffic = TEST_MANIFEST.command("traffic.report")!!
        assertEquals("KECELAKAAN", matcher.enumValueIn("ada tabrakan", traffic.param("type")!!))
        assertNull(matcher.enumValueIn("tidak tahu", traffic.param("type")!!))
        assertEquals("macet", matcher.displayValue(traffic.param("type")!!, "MACET"))
    }
}

class DeeplinksTest {

    @Test
    fun buildEncodesValuesAndFillsContext() {
        val url = Deeplinks.build("ojolmitra://trip/{tripId}/chat?text={message}", mapOf("message" to "Saya di depan? ya & ok"), TRIP)
        assertEquals("ojolmitra://trip/T-9/chat?text=Saya%20di%20depan%3F%20ya%20%26%20ok", url)
        assertEquals("app://x/%C3%A9t%C3%A9", Deeplinks.build("app://x/{v}", mapOf("v" to "été"), VoiceContext.EMPTY))
        assertNull(Deeplinks.build("app://x/{missing}", emptyMap(), VoiceContext.EMPTY))
        assertEquals("app://x/Jl.%20Gatot%20Subroto", Deeplinks.build("app://x/{context.currentRoad}", emptyMap(), TRIP))
    }

    @Test
    fun renderFillsSpokenTemplatesAndTidiesSpaces() {
        assertEquals("Laporan macet di Jl. Gatot Subroto terkirim.", Deeplinks.render("Laporan {type} di {context.currentRoad} terkirim.", mapOf("type" to "macet"), TRIP))
        assertEquals("Maaf, kurang jelas.", Deeplinks.render("Maaf, kurang jelas. {context.hint}", emptyMap(), TRIP))
    }

    @Test
    fun allowlistChecksSchemeAndHost() {
        val schemeOnly = DispatchConfig(allowedSchemes = listOf("ojolmitra"))
        assertTrue(Deeplinks.isAllowed("ojolmitra://order/1/accept", schemeOnly))
        assertTrue(!Deeplinks.isAllowed("https://evil.example/x", schemeOnly))
        assertTrue(!Deeplinks.isAllowed("ojolmitra://order", DispatchConfig()))
        val withHost = DispatchConfig(allowedSchemes = listOf("ojolmitra"), allowedHosts = listOf("trip"))
        assertTrue(Deeplinks.isAllowed("ojolmitra://trip/9/chat?text=a", withHost))
        assertTrue(!Deeplinks.isAllowed("ojolmitra://order/1/accept", withHost))
    }

    @Test
    fun parseDecodes() {
        val p = assertNotNull(Deeplinks.parse("ojolmitra://trip/T-9/chat?text=Saya%20di%20depan%3F&x=1"))
        assertEquals("ojolmitra", p.scheme)
        assertEquals("trip", p.host)
        assertEquals(listOf("T-9", "chat"), p.pathSegments)
        assertEquals("Saya di depan?", p.query["text"])
        assertEquals("1", p.query["x"])
        assertNull(Deeplinks.parse("not a link"))
    }
}
