package dev.alexdev404.droidctl.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The coordinate transform, over every combination of Host and Target
 * orientation the app can be in.
 *
 * The letterbox margins are the interesting part: a tap there is not on the
 * Target at all, and mapping it to the nearest edge produces phantom edge
 * swipes (back gestures, notification pulls) that the user never made.
 */
class ViewportMappingTest {

    @Test
    fun `portrait Target in a matching portrait view has no letterbox`() {
        val mapping = ViewportMapping(viewWidth = 1080, viewHeight = 2400, targetWidth = 1080, targetHeight = 2400)
        assertEquals(1f, mapping.scale, 0.0001f)
        assertEquals(0f, mapping.contentLeft, 0.0001f)
        assertEquals(0f, mapping.contentTop, 0.0001f)
        assertEquals(TargetPoint(540, 1200), mapping.toTarget(540f, 1200f))
    }

    @Test
    fun `a portrait Target in a wider view is letterboxed left and right`() {
        // 1080x2400 Target inside a 2400x1080 (landscape) Host view.
        val mapping = ViewportMapping(viewWidth = 2400, viewHeight = 1080, targetWidth = 1080, targetHeight = 2400)
        assertEquals(0.45f, mapping.scale, 0.0001f)
        assertEquals(486f, mapping.contentWidth, 0.01f)
        assertEquals(1080f, mapping.contentHeight, 0.01f)
        assertEquals(957f, mapping.contentLeft, 0.01f)
        assertEquals(0f, mapping.contentTop, 0.01f)

        // Centre of the video maps to the centre of the Target.
        assertEquals(TargetPoint(540, 1200), mapping.toTarget(957f + 243f, 540f))
        // Left margin is not on the Target.
        assertNull(mapping.toTarget(100f, 540f))
        // Right margin is not either.
        assertNull(mapping.toTarget(2300f, 540f))
    }

    @Test
    fun `a landscape Target in a portrait view is letterboxed top and bottom`() {
        val mapping = ViewportMapping(viewWidth = 1080, viewHeight = 2400, targetWidth = 2400, targetHeight = 1080)
        assertEquals(0.45f, mapping.scale, 0.0001f)
        assertEquals(1080f, mapping.contentWidth, 0.01f)
        assertEquals(486f, mapping.contentHeight, 0.01f)
        assertEquals(0f, mapping.contentLeft, 0.01f)
        assertEquals(957f, mapping.contentTop, 0.01f)

        assertEquals(TargetPoint(1200, 540), mapping.toTarget(540f, 957f + 243f))
        assertNull(mapping.toTarget(540f, 100f))
        assertNull(mapping.toTarget(540f, 2300f))
    }

    @Test
    fun `corners map to the Target's own corners`() {
        val mapping = ViewportMapping(viewWidth = 540, viewHeight = 1200, targetWidth = 1080, targetHeight = 2400)
        assertEquals(TargetPoint(0, 0), mapping.toTarget(0f, 0f))
        // The far corner clamps to the last addressable pixel, not to the count.
        assertEquals(TargetPoint(1079, 2399), mapping.toTarget(540f, 1200f))
    }

    @Test
    fun `clamping is available for points off the video`() {
        val mapping = ViewportMapping(viewWidth = 2400, viewHeight = 1080, targetWidth = 1080, targetHeight = 2400)
        // Far left of the view is left of the video: clamps to column 0.
        assertEquals(TargetPoint(0, 540), mapping.clampToTarget(0f, 243f))
        // Far right clamps to the last column.
        assertEquals(TargetPoint(1079, 540), mapping.clampToTarget(2400f, 243f))
    }

    @Test
    fun `an unusable mapping reports itself rather than dividing by zero`() {
        val mapping = ViewportMapping(viewWidth = 0, viewHeight = 0, targetWidth = 1080, targetHeight = 2400)
        assertFalse(mapping.isUsable)
        assertNull(mapping.toTarget(10f, 10f))
        assertEquals(TargetPoint(0, 0), mapping.clampToTarget(10f, 10f))
    }

    @Test
    fun `the video edges are inside the video`() {
        val mapping = ViewportMapping(viewWidth = 2400, viewHeight = 1080, targetWidth = 1080, targetHeight = 2400)
        assertTrue(mapping.contains(mapping.contentLeft, 0f))
        assertTrue(mapping.contains(mapping.contentLeft + mapping.contentWidth, 1080f))
        assertFalse(mapping.contains(mapping.contentLeft - 1f, 540f))
        assertFalse(mapping.contains(mapping.contentLeft + mapping.contentWidth + 1f, 540f))
    }
}
