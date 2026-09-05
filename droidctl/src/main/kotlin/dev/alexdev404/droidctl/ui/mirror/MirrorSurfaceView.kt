package dev.alexdev404.droidctl.ui.mirror

import android.content.Context
import android.view.MotionEvent
import android.view.SurfaceView
import android.view.View

/**
 * The surface the Target's video is rendered into, and the source of every
 * touch DroidCtl forwards.
 *
 * A subclass rather than a bare [SurfaceView] with an `OnTouchListener` for one
 * reason: a view that consumes touches must also honour [performClick], or
 * accessibility services that synthesise a click (TalkBack's double-tap, a
 * switch-access selection) reach a view that ignores them.
 */
class MirrorSurfaceView(context: Context) : SurfaceView(context) {

    /** Called for every touch, to be forwarded to the Target. */
    var onTouch: ((MotionEvent) -> Unit)? = null

    init {
        // Nothing on this view is readable by a screen reader; the overlay
        // controls carry the content descriptions.
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        onTouch?.invoke(event)
        if (event.actionMasked == MotionEvent.ACTION_UP) {
            performClick()
        }
        // Consume everything: returning false for ACTION_DOWN would stop the
        // rest of the gesture from ever arriving.
        return true
    }

    /** Present so a synthesised click still reaches the view's click handling. */
    override fun performClick(): Boolean = super.performClick()
}
