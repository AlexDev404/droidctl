package dev.alexdev404.droidctl.input

import dev.alexdev404.droidctl.scrcpy.ControlMessage
import dev.alexdev404.droidctl.scrcpy.ScrcpyProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pointer lifecycle and coordinate mapping.
 *
 * Two behaviours here are easy to get wrong and expensive when wrong:
 *
 *  - only DOWN / UP / MOVE go on the wire (the server derives POINTER_DOWN and
 *    POINTER_UP itself from the pointer index it assigned), and
 *  - a gesture that starts in the letterbox margin must be dropped whole, not
 *    clamped onto the Target's edge.
 */
class TouchMapperTest {

    private fun mapper(
        viewWidth: Int = 1080,
        viewHeight: Int = 2400,
        targetWidth: Int = 1080,
        targetHeight: Int = 2400,
    ) = TouchMapper().apply { updateMapping(viewWidth, viewHeight, targetWidth, targetHeight) }

    private fun touches(messages: List<ControlMessage>) =
        messages.filterIsInstance<ControlMessage.InjectTouch>()

    @Test
    fun `a single tap sends down then up`() {
        val mapper = mapper()
        val down = touches(mapper.onDown(PointerSample(0, 100f, 200f)))
        assertEquals(1, down.size)
        assertEquals(ScrcpyProtocol.MOTION_ACTION_DOWN, down[0].action)
        assertEquals(0L, down[0].pointerId)
        assertEquals(100, down[0].x)
        assertEquals(200, down[0].y)
        assertEquals(1.0f, down[0].pressure, 0f)

        val up = touches(mapper.onUp(PointerSample(0, 100f, 200f)))
        assertEquals(1, up.size)
        assertEquals(ScrcpyProtocol.MOTION_ACTION_UP, up[0].action)
        assertEquals(0.0f, up[0].pressure, 0f)
        assertEquals(false, mapper.hasActivePointers)
    }

    @Test
    fun `a second finger going down is still ACTION_DOWN on the wire`() {
        val mapper = mapper()
        mapper.onDown(PointerSample(0, 10f, 10f))
        val second = touches(mapper.onDown(PointerSample(1, 20f, 20f)))
        assertEquals(1, second.size)
        // Not ACTION_POINTER_DOWN: Controller.injectTouch ORs the pointer index
        // in itself, from its own table.
        assertEquals(ScrcpyProtocol.MOTION_ACTION_DOWN, second[0].action)
        assertEquals(1L, second[0].pointerId)
    }

    @Test
    fun `scrcpy pointer ids are allocated independently of Host pointer ids`() {
        val mapper = mapper()
        // Android is free to hand out any pointer ids at all.
        val first = touches(mapper.onDown(PointerSample(7, 10f, 10f))).single()
        val second = touches(mapper.onDown(PointerSample(3, 20f, 20f))).single()
        assertEquals(0L, first.pointerId)
        assertEquals(1L, second.pointerId)
    }

    @Test
    fun `a released pointer id is reused by the next finger`() {
        val mapper = mapper()
        mapper.onDown(PointerSample(0, 10f, 10f))
        mapper.onDown(PointerSample(1, 20f, 20f))
        mapper.onUp(PointerSample(0, 10f, 10f))
        val third = touches(mapper.onDown(PointerSample(2, 30f, 30f))).single()
        assertEquals("the freed id 0 should be reused", 0L, third.pointerId)
    }

    @Test
    fun `moves are emitted for every active pointer`() {
        val mapper = mapper()
        mapper.onDown(PointerSample(0, 10f, 10f))
        mapper.onDown(PointerSample(1, 20f, 20f))
        val moves = touches(
            mapper.onMove(listOf(PointerSample(0, 11f, 11f), PointerSample(1, 21f, 21f)))
        )
        assertEquals(2, moves.size)
        assertTrue(moves.all { it.action == ScrcpyProtocol.MOTION_ACTION_MOVE })
        assertEquals(setOf(0L, 1L), moves.map { it.pointerId }.toSet())
    }

    @Test
    fun `moves for a pointer that never went down are ignored`() {
        val mapper = mapper()
        assertEquals(emptyList<ControlMessage>(), mapper.onMove(listOf(PointerSample(0, 10f, 10f))))
    }

    @Test
    fun `a tap in the letterbox margin is discarded, not clamped`() {
        // 1080x2400 Target in a 2400x1080 view: margins of 957px either side.
        val mapper = mapper(viewWidth = 2400, viewHeight = 1080)
        assertEquals(emptyList<ControlMessage>(), mapper.onDown(PointerSample(0, 100f, 540f)))
        assertEquals(false, mapper.hasActivePointers)
    }

    @Test
    fun `a gesture that started in the margin stays discarded`() {
        val mapper = mapper(viewWidth = 2400, viewHeight = 1080)
        mapper.onDown(PointerSample(0, 100f, 540f))
        assertEquals(emptyList<ControlMessage>(), mapper.onMove(listOf(PointerSample(0, 1200f, 540f))))
        assertEquals(emptyList<ControlMessage>(), mapper.onUp(PointerSample(0, 1200f, 540f)))
    }

    @Test
    fun `a drag that leaves the video is clamped rather than dropped`() {
        val mapper = mapper(viewWidth = 2400, viewHeight = 1080)
        mapper.onDown(PointerSample(0, 1200f, 540f))
        val move = touches(mapper.onMove(listOf(PointerSample(0, 0f, 540f)))).single()
        // A drag whose moves vanish looks to the Target like a finger that froze.
        assertEquals(0, move.x)
        val up = touches(mapper.onUp(PointerSample(0, 0f, 540f))).single()
        assertEquals(ScrcpyProtocol.MOTION_ACTION_UP, up.action)
    }

    @Test
    fun `cancel releases every pointer with ACTION_UP`() {
        val mapper = mapper()
        mapper.onDown(PointerSample(0, 10f, 10f))
        mapper.onDown(PointerSample(1, 20f, 20f))
        val cancelled = touches(
            mapper.onCancel(listOf(PointerSample(0, 10f, 10f), PointerSample(1, 20f, 20f)))
        )
        assertEquals(2, cancelled.size)
        // ACTION_CANCEL would leave the server's pointer table populated: it
        // only clears a pointer on ACTION_UP.
        assertTrue(cancelled.all { it.action == ScrcpyProtocol.MOTION_ACTION_UP })
        assertTrue(cancelled.all { it.pressure == 0.0f })
        assertEquals(false, mapper.hasActivePointers)
    }

    @Test
    fun `messages carry the Target's current dimensions`() {
        val mapper = mapper(targetWidth = 720, targetHeight = 1280)
        val down = touches(mapper.onDown(PointerSample(0, 540f, 1200f))).single()
        assertEquals(720, down.screenWidth)
        assertEquals(1280, down.screenHeight)
    }

    @Test
    fun `a Target rotation changes the dimensions the next event carries`() {
        // 720x1280 inside 1080x2400 letterboxes 240px top and bottom, so the
        // finger has to go down below that margin to be tracked at all.
        val mapper = mapper(targetWidth = 720, targetHeight = 1280)
        mapper.onDown(PointerSample(0, 100f, 400f))
        // The Target rotated: the stream sends a new session record.
        mapper.updateMapping(1080, 2400, 1280, 720)
        val move = touches(mapper.onMove(listOf(PointerSample(0, 100f, 400f)))).single()
        // Stale dimensions here would make the server rescale every tap wrongly.
        assertEquals(1280, move.screenWidth)
        assertEquals(720, move.screenHeight)
    }

    @Test
    fun `nothing is emitted before the Target size is known`() {
        val mapper = TouchMapper()
        assertEquals(emptyList<ControlMessage>(), mapper.onDown(PointerSample(0, 10f, 10f)))
    }

    @Test
    fun `scroll maps through the same transform and is discarded in the margin`() {
        val mapper = mapper(viewWidth = 2400, viewHeight = 1080)
        val scroll = mapper.scroll(1200f, 540f, 0f, -1f)
            .filterIsInstance<ControlMessage.InjectScroll>()
            .single()
        assertEquals(540, scroll.x)
        assertEquals(-1f, scroll.vScroll, 0f)
        assertEquals(emptyList<ControlMessage>(), mapper.scroll(10f, 540f, 0f, -1f))
    }
}
