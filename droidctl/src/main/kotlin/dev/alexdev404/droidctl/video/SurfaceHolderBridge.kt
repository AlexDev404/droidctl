package dev.alexdev404.droidctl.video

import android.view.Surface
import android.view.SurfaceHolder
import dev.alexdev404.droidctl.DroidCtlLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first

/** The mirror view's size in Host pixels, or (0, 0) before it is laid out. */
data class ViewSize(val width: Int, val height: Int)

/**
 * Bridges a `SurfaceView`'s callback-based lifecycle into flows the session can
 * suspend on.
 *
 * The decoder cannot be configured before a [Surface] exists, and the surface
 * goes away and comes back every time the Host is backgrounded, so the session
 * waits on [awaitSurface] rather than assuming one is there.
 */
class SurfaceHolderBridge : SurfaceHolder.Callback {

    private val log = DroidCtlLog.video

    private val _surface = MutableStateFlow<Surface?>(null)
    val surface: StateFlow<Surface?> = _surface.asStateFlow()

    private val _viewSize = MutableStateFlow(ViewSize(0, 0))
    val viewSize: StateFlow<ViewSize> = _viewSize.asStateFlow()

    /**
     * Called on the main thread, synchronously with the `SurfaceHolder`
     * callback, with the new surface or null when it is being destroyed.
     *
     * Synchronous on purpose: the surface stays valid only until
     * `surfaceDestroyed` returns, so a decoder that learns about the loss
     * through a flow may still try to render into a surface that is already
     * gone. Reacting inside the callback closes that window.
     */
    @Volatile
    var onSurfaceLifecycle: ((Surface?) -> Unit)? = null

    /** Suspends until a surface is available, returning it. */
    suspend fun awaitSurface(): Surface = _surface.filterNotNull().first()

    override fun surfaceCreated(holder: SurfaceHolder) {
        log.d("Mirror surface created")
        _surface.value = holder.surface
        onSurfaceLifecycle?.invoke(holder.surface)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        log.d("Mirror surface ${width}x$height")
        _viewSize.value = ViewSize(width, height)
        _surface.value = holder.surface
        onSurfaceLifecycle?.invoke(holder.surface)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        log.d("Mirror surface destroyed")
        // Before clearing the flow, so the decoder stops rendering while the
        // surface is still valid enough to be handed back.
        onSurfaceLifecycle?.invoke(null)
        _surface.value = null
    }
}
