package dev.alexdev404.droidctl.input

import android.view.MotionEvent

/**
 * Unpacks an Android [MotionEvent] into the framework-free samples [TouchMapper]
 * consumes.
 *
 * Everything interesting (coordinate transform, pointer bookkeeping, gesture
 * lifecycle) lives in [TouchMapper] so it can be tested on the JVM. This file
 * is deliberately dumb.
 */
object MotionEventAdapter {

    /**
     * Feeds [event] to [mapper] and returns the control messages to send.
     *
     * `ACTION_MOVE` replays every historical sample the platform batched, in
     * order, before the current one: without that, a fast swipe reaches the
     * Target as a couple of far-apart jumps and is recognised as a fling with
     * the wrong velocity, or not at all.
     */
    fun toMessages(event: MotionEvent, mapper: TouchMapper): List<dev.alexdev404.droidctl.scrcpy.ControlMessage> =
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val index = event.actionIndex
                mapper.onDown(
                    PointerSample(event.getPointerId(index), event.getX(index), event.getY(index))
                )
            }

            MotionEvent.ACTION_MOVE -> buildList {
                for (h in 0 until event.historySize) {
                    addAll(mapper.onMove(historicalSamples(event, h)))
                }
                addAll(mapper.onMove(currentSamples(event)))
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val index = event.actionIndex
                mapper.onUp(
                    PointerSample(event.getPointerId(index), event.getX(index), event.getY(index))
                )
            }

            MotionEvent.ACTION_CANCEL -> mapper.onCancel(currentSamples(event))

            else -> emptyList()
        }

    private fun currentSamples(event: MotionEvent): List<PointerSample> =
        (0 until event.pointerCount).map { i ->
            PointerSample(event.getPointerId(i), event.getX(i), event.getY(i))
        }

    private fun historicalSamples(event: MotionEvent, position: Int): List<PointerSample> =
        (0 until event.pointerCount).map { i ->
            PointerSample(
                event.getPointerId(i),
                event.getHistoricalX(i, position),
                event.getHistoricalY(i, position),
            )
        }
}
