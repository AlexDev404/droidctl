package dev.alexdev404.droidctl.input

import dev.alexdev404.droidctl.scrcpy.ControlMessage
import dev.alexdev404.droidctl.scrcpy.ScrcpyProtocol
import kotlin.math.min
import kotlin.math.roundToInt

/** A point on the Target's screen, in the Target's own pixels. */
data class TargetPoint(val x: Int, val y: Int)

/**
 * Aspect-fit mapping from Host view coordinates to Target screen coordinates.
 *
 * The mirror `SurfaceView` fills the Host screen but the Target's video is
 * letterboxed inside it, so part of the view is dead margin. This type is a
 * pure value: recomputing it on a Target rotation, a Host rotation or a view
 * resize is a matter of constructing a new one.
 */
data class ViewportMapping(
    val viewWidth: Int,
    val viewHeight: Int,
    val targetWidth: Int,
    val targetHeight: Int,
) {
    /** Pixels of view per pixel of Target. Zero when any dimension is unknown. */
    val scale: Float =
        if (viewWidth <= 0 || viewHeight <= 0 || targetWidth <= 0 || targetHeight <= 0) 0f
        else min(viewWidth.toFloat() / targetWidth, viewHeight.toFloat() / targetHeight)

    /** Width of the letterboxed video inside the view. */
    val contentWidth: Float = targetWidth * scale

    /** Height of the letterboxed video inside the view. */
    val contentHeight: Float = targetHeight * scale

    /** Left edge of the video inside the view; the margin is everything left of it. */
    val contentLeft: Float = (viewWidth - contentWidth) / 2f

    /** Top edge of the video inside the view. */
    val contentTop: Float = (viewHeight - contentHeight) / 2f

    val isUsable: Boolean get() = scale > 0f

    /** True when ([viewX], [viewY]) falls on the video rather than in the margin. */
    fun contains(viewX: Float, viewY: Float): Boolean =
        isUsable &&
            viewX >= contentLeft && viewX <= contentLeft + contentWidth &&
            viewY >= contentTop && viewY <= contentTop + contentHeight

    /**
     * Maps a view point onto the Target, or returns null if it is in the margin.
     *
     * Callers that must produce a point regardless (finishing a drag that left
     * the video) use [clampToTarget] instead. Discarding rather than clamping
     * matters for the *start* of a gesture: clamping a tap in the margin lands
     * it on the very edge of the Target, which reads as an edge swipe and
     * triggers back gestures nobody asked for.
     */
    fun toTarget(viewX: Float, viewY: Float): TargetPoint? =
        if (!contains(viewX, viewY)) null else clampToTarget(viewX, viewY)

    /** Maps a view point onto the Target, clamped to its bounds. */
    fun clampToTarget(viewX: Float, viewY: Float): TargetPoint {
        if (!isUsable) return TargetPoint(0, 0)
        val x = ((viewX - contentLeft) / scale).roundToInt().coerceIn(0, targetWidth - 1)
        val y = ((viewY - contentTop) / scale).roundToInt().coerceIn(0, targetHeight - 1)
        return TargetPoint(x, y)
    }
}

/** One finger in a Host touch event, in view coordinates. */
data class PointerSample(val pointerId: Int, val x: Float, val y: Float)

/**
 * Turns Host touch gestures into scrcpy touch messages.
 *
 * Deliberately free of any `android.view` type so that the whole coordinate and
 * pointer-lifecycle logic is unit-testable on the JVM; the Activity-side adapter
 * ([MotionEventAdapter]) does nothing but unpack `MotionEvent` into
 * [PointerSample]s.
 *
 * Two behaviours are worth knowing about:
 *
 *  - Only `ACTION_DOWN`, `ACTION_UP` and `ACTION_MOVE` ever go on the wire. The
 *    server's `Controller.injectTouch` derives `ACTION_POINTER_DOWN` /
 *    `ACTION_POINTER_UP` itself from the pointer index it assigned to our
 *    64-bit pointer id, so a second finger going down is still `ACTION_DOWN`.
 *  - A gesture that starts in the letterbox margin is dropped entirely: its
 *    pointer is never registered, so its moves and its up are dropped too.
 */
class TouchMapper {

    private val log = dev.alexdev404.droidctl.DroidCtlLog.input

    /** Android pointer id -> scrcpy pointer id, for pointers currently down. */
    private val activePointers = LinkedHashMap<Int, Long>()

    var mapping: ViewportMapping = ViewportMapping(0, 0, 0, 0)
        private set

    /** Recomputes the transform. Call on Target rotation, Host rotation and resize. */
    fun updateMapping(viewWidth: Int, viewHeight: Int, targetWidth: Int, targetHeight: Int) {
        val next = ViewportMapping(viewWidth, viewHeight, targetWidth, targetHeight)
        if (next != mapping) {
            log.d(
                "viewport ${viewWidth}x$viewHeight -> target ${targetWidth}x$targetHeight " +
                    "(scale=%.3f, letterbox=%.1f,%.1f)".format(next.scale, next.contentLeft, next.contentTop)
            )
            mapping = next
        }
    }

    /** True while at least one finger is down and being forwarded. */
    val hasActivePointers: Boolean get() = activePointers.isNotEmpty()

    /**
     * A finger went down. Returns the message to send, or nothing if the touch
     * landed in the letterbox margin or the Target size is not known yet.
     */
    fun onDown(sample: PointerSample): List<ControlMessage> {
        if (!mapping.isUsable) return emptyList()
        val point = mapping.toTarget(sample.x, sample.y) ?: return emptyList()
        if (activePointers.containsKey(sample.pointerId)) {
            // Should not happen, but a duplicate DOWN would otherwise leak an id.
            log.w("duplicate DOWN for Host pointer ${sample.pointerId}")
            return emptyList()
        }
        val scrcpyId = allocatePointerId()
        activePointers[sample.pointerId] = scrcpyId
        return listOf(touch(ScrcpyProtocol.MOTION_ACTION_DOWN, scrcpyId, point, PRESSURE_DOWN))
    }

    /**
     * Fingers moved. [samples] must cover every pointer currently down; callers
     * should also replay historical samples so fast drags are not decimated.
     *
     * Points that leave the video are clamped rather than dropped: a drag whose
     * moves vanish looks to the Target like a finger that froze mid-gesture.
     */
    fun onMove(samples: List<PointerSample>): List<ControlMessage> {
        if (!mapping.isUsable) return emptyList()
        return samples.mapNotNull { sample ->
            val scrcpyId = activePointers[sample.pointerId] ?: return@mapNotNull null
            val point = mapping.clampToTarget(sample.x, sample.y)
            touch(ScrcpyProtocol.MOTION_ACTION_MOVE, scrcpyId, point, PRESSURE_DOWN)
        }
    }

    /** A finger came up. Always clamps: the Target must see the release. */
    fun onUp(sample: PointerSample): List<ControlMessage> {
        val scrcpyId = activePointers.remove(sample.pointerId) ?: return emptyList()
        if (!mapping.isUsable) return emptyList()
        val point = mapping.clampToTarget(sample.x, sample.y)
        return listOf(touch(ScrcpyProtocol.MOTION_ACTION_UP, scrcpyId, point, PRESSURE_UP))
    }

    /**
     * The gesture was cancelled (the Host took the touch stream away, or the
     * session is tearing down).
     *
     * Every active pointer is released with `ACTION_UP` rather than
     * `ACTION_CANCEL`: the server only clears a pointer from its table on
     * `ACTION_UP`, so a cancel would leave phantom fingers pressed on the
     * Target for the rest of the session.
     */
    fun onCancel(lastKnown: List<PointerSample> = emptyList()): List<ControlMessage> {
        val byId = lastKnown.associateBy { it.pointerId }
        val messages = activePointers.map { (androidId, scrcpyId) ->
            val sample = byId[androidId]
            val point = if (sample != null && mapping.isUsable) {
                mapping.clampToTarget(sample.x, sample.y)
            } else {
                TargetPoint(0, 0)
            }
            touch(ScrcpyProtocol.MOTION_ACTION_UP, scrcpyId, point, PRESSURE_UP)
        }
        activePointers.clear()
        return messages
    }

    /** Forgets every pointer without emitting anything. For teardown after the socket is gone. */
    fun reset() {
        activePointers.clear()
    }

    /**
     * A scroll gesture, in the Target's own coordinates.
     *
     * [hScroll]/[vScroll] are in scroll units, positive meaning right/down,
     * and are clamped to the protocol's [-16, 16] range during serialization.
     */
    fun scroll(viewX: Float, viewY: Float, hScroll: Float, vScroll: Float): List<ControlMessage> {
        if (!mapping.isUsable) return emptyList()
        val point = mapping.toTarget(viewX, viewY) ?: return emptyList()
        return listOf(
            ControlMessage.InjectScroll(
                x = point.x,
                y = point.y,
                screenWidth = mapping.targetWidth,
                screenHeight = mapping.targetHeight,
                hScroll = hScroll,
                vScroll = vScroll,
            )
        )
    }

    private fun touch(action: Int, pointerId: Long, point: TargetPoint, pressure: Float) =
        ControlMessage.InjectTouch(
            action = action,
            pointerId = pointerId,
            x = point.x,
            y = point.y,
            screenWidth = mapping.targetWidth,
            screenHeight = mapping.targetHeight,
            pressure = pressure,
        )

    /**
     * Smallest non-negative id not currently in use.
     *
     * Host pointer ids are not reused as scrcpy pointer ids: the two namespaces
     * are independent, and the server keys its own pointer table off whatever
     * 64-bit value we send.
     */
    private fun allocatePointerId(): Long {
        val inUse = activePointers.values.toHashSet()
        var candidate = 0L
        while (candidate in inUse) candidate++
        return candidate
    }

    private companion object {
        const val PRESSURE_DOWN = 1.0f
        const val PRESSURE_UP = 0.0f
    }
}
