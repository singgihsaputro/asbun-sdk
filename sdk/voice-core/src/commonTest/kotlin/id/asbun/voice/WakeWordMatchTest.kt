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

    // The recognizer often merges the phrase into one word (seen on device: "alomitra", "alomitrah").
    @Test fun mergedPhraseMatches() = assertEquals("terima", m.wakeRemainder("halomitra terima", phrases))
    @Test fun mergedPhraseOneLetterOff() = assertEquals("sudah sampai", m.wakeRemainder("alomitra sudah sampai", phrases))
    @Test fun longMergedPhraseTwoLettersOff() = assertEquals("terima", m.wakeRemainder("Alomitrah, terima", phrases))
    @Test fun mergedPhraseAlone() = assertEquals("", m.wakeRemainder("alomitra", phrases))

    // Real Indonesian words that sound close stay rejected: they would wake the app in normal speech.
    @Test fun kalauMitraIsNotTheWakeWord() = assertNull(m.wakeRemainder("kalau mitra sudah sampai", phrases))
    @Test fun alamMitraIsNotTheWakeWord() = assertNull(m.wakeRemainder("alam mitra terima", phrases))
    @Test fun mergedWordTooFarOff() = assertNull(m.wakeRemainder("kalaumitra terima", phrases)) // 3 edits
    @Test fun shortMergedPhraseStaysStrict() = assertNull(m.wakeRemainder("habus gas", listOf("hai bos"))) // 7 letters: 1 edit only
}
