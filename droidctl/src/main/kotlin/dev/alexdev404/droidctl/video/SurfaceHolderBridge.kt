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
 * Bridges a [SurfaceView]'s callback-based lifecycle into flows the session can
 * suspend on.
 *
 * The decoder cannot be configured before a [Surface] exists, and the surface
 * can go away and come back (the Host rotates, the app is backgrounded), so the
 * session waits on [awaitSurface] rather than assuming one is there.
 */
class SurfaceHolderBridge : SurfaceHolder.Callback {

    private val log = DroidCtlLog.video

    private val _surface = MutableStateFlow<Surface?>(null)
    val surface: StateFlow<Surface?> = _surface.asStateFlow()

    private val _viewSize = MutableStateFlow(ViewSize(0, 0))
    val viewSize: StateFlow<ViewSize> = _viewSize.asStateFlow()

    /** Suspends until a surface is available, returning it. */
    suspend fun awaitSurface(): Surface = _surface.filterNotNull().first()

    override fun surfaceCreated(holder: SurfaceHolder) {
        log.d("Mirror surface created")
        _surface.value = holder.surface
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        log.d("Mirror surface ${width}x$height")
        _viewSize.value = ViewSize(width, height)
        _surface.value = holder.surface
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        log.d("Mirror surface destroyed")
        _surface.value = null
    }
}
