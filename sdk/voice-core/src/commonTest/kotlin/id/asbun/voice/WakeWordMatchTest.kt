package id.asbun.voice

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WakeWordMatchTest {
    private val m = KeywordMatcher(TEST_MANIFEST, "id-ID")
    private val phrases = listOf("halo mitra")

    @Test fun phraseAlone() = assertEquals("", m.wakeRemainder("Halo Mitra", phrases))
    @Test fun phraseThenCommandKeepsOriginalWords() =
        assertEquals("Terima order", m.wakeRemainder("Halo Mitra, Terima order", phrases))
    @Test fun oneLetterOffStillMatches() = assertEquals("terima", m.wakeRemainder("halo mitri terima", phrases))
    @Test fun shortWordsNeedExactMatch() = assertNull(m.wakeRemainder("hal mitra terima", phrases))
    @Test fun phraseMustStartTheText() = assertNull(m.wakeRemainder("tolong halo mitra terima", phrases))
    @Test fun chatterIsIgnored() = assertNull(m.wakeRemainder("iya bu sebentar lagi sampai", phrases))
    @Test fun partialPhraseIsNotEnough() = assertNull(m.wakeRemainder("halo", phrases))
    @Test fun anyConfiguredPhrase() = assertEquals("gas", m.wakeRemainder("hai mitra gas", listOf("halo mitra", "hai mitra")))
}
