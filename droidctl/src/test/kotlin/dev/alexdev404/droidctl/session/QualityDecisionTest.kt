package dev.alexdev404.droidctl.session

import dev.alexdev404.droidctl.model.ConnectionQuality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QualityDecisionTest {

    @Test
    fun `a fresh measurement wins`() {
        val decision = QualityDecision.automatic(
            freshBitsPerSecond = 2_000_000,
            rememberedBitsPerSecond = 50_000_000,
            pushWasTooBriefToTime = false,
        )
        assertEquals(ConnectionQuality.Medium, decision.quality)
        assertTrue(decision.reason.contains("2000 kbps"))
    }

    @Test
    fun `a remembered measurement is used when nothing was pushed`() {
        // The normal case from the second session onwards: the Target already
        // has the jar, so there is no push to time.
        val decision = QualityDecision.automatic(
            freshBitsPerSecond = null,
            rememberedBitsPerSecond = 2_000_000,
            pushWasTooBriefToTime = false,
        )
        assertEquals(ConnectionQuality.Medium, decision.quality)
        assertTrue(decision.reason.contains("earlier session"))
    }

    @Test
    fun `a push too brief to time means a fast link, not a slow one`() {
        val decision = QualityDecision.automatic(
            freshBitsPerSecond = null,
            rememberedBitsPerSecond = null,
            pushWasTooBriefToTime = true,
        )
        assertEquals(ConnectionQuality.entries.last(), decision.quality)
    }

    @Test
    fun `an untimed push beats a stale remembered figure`() {
        // The push just happened; whatever it says outranks history, even when
        // all it says is "too fast to measure".
        val decision = QualityDecision.automatic(
            freshBitsPerSecond = null,
            rememberedBitsPerSecond = 300_000,
            pushWasTooBriefToTime = true,
        )
        assertEquals(ConnectionQuality.entries.last(), decision.quality)
    }

    @Test
    fun `with nothing measured it lands mid-ladder`() {
        val decision = QualityDecision.automatic(null, null, false)
        assertEquals(ConnectionQuality.UNMEASURED_DEFAULT, decision.quality)
        assertTrue(decision.reason.contains("nothing measured"))
    }

    @Test
    fun `a slow measurement picks the bottom of the ladder`() {
        val decision = QualityDecision.automatic(300_000, null, false)
        assertEquals(ConnectionQuality.Lowest, decision.quality)
    }

    @Test
    fun `every decision explains itself`() {
        val decisions = listOf(
            QualityDecision.automatic(1_000_000, null, false),
            QualityDecision.automatic(null, 1_000_000, false),
            QualityDecision.automatic(null, null, true),
            QualityDecision.automatic(null, null, false),
        )
        // The reason ends up in the log; a decision nobody can account for after
        // the fact is what makes "it ignored my setting" impossible to check.
        assertTrue(decisions.all { it.reason.isNotBlank() })
    }
}
