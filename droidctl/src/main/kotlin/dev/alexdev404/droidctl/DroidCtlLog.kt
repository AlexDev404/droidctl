package dev.alexdev404.droidctl

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Structured logging for DroidCtl.
 *
 * One tag per module (`DroidCtl/Adb`, `DroidCtl/Proto`, ...). Every
 * session-scoped line carries the scid so that logs from overlapping or
 * successive sessions can be told apart.
 *
 * Every line also lands in [LogBuffer] so the in-app debug pane can show it;
 * `adb logcat` is not available to a user holding the Host in their hand.
 */
class DroidCtlLog(private val tag: String, private val scid: String? = null) {

    /** Returns a logger with the same tag that stamps every line with [scid]. */
    fun withScid(scid: String): DroidCtlLog = DroidCtlLog(tag, scid)

    fun d(message: String) = log(Log.DEBUG, message, null)
    fun i(message: String) = log(Log.INFO, message, null)
    fun w(message: String, t: Throwable? = null) = log(Log.WARN, message, t)
    fun e(message: String, t: Throwable? = null) = log(Log.ERROR, message, t)

    private fun log(priority: Int, message: String, t: Throwable?) {
        val line = if (scid != null) "[$scid] $message" else message
        Log.println(priority, tag, if (t == null) line else "$line: ${t.stackTraceToString()}")
        LogBuffer.append(LogLine(priority, tag, line, t?.toString()))
    }

    companion object {
        const val TAG_ADB = "DroidCtl/Adb"
        const val TAG_PROTO = "DroidCtl/Proto"
        const val TAG_VIDEO = "DroidCtl/Video"
        const val TAG_INPUT = "DroidCtl/Input"
        const val TAG_SESSION = "DroidCtl/Session"
        const val TAG_SERVER = "DroidCtl/ScrcpyServer"

        val adb = DroidCtlLog(TAG_ADB)
        val proto = DroidCtlLog(TAG_PROTO)
        val video = DroidCtlLog(TAG_VIDEO)
        val input = DroidCtlLog(TAG_INPUT)
        val session = DroidCtlLog(TAG_SESSION)
        val server = DroidCtlLog(TAG_SERVER)
    }
}

data class LogLine(
    val priority: Int,
    val tag: String,
    val message: String,
    val throwable: String?,
) {
    val priorityChar: Char
        get() = when (priority) {
            Log.VERBOSE -> 'V'
            Log.DEBUG -> 'D'
            Log.INFO -> 'I'
            Log.WARN -> 'W'
            Log.ERROR -> 'E'
            else -> '?'
        }
}

/** Bounded in-memory tail of the log, rendered by the debug pane. */
object LogBuffer {
    private const val CAPACITY = 500

    private val lock = Any()
    private val lines = ArrayDeque<LogLine>(CAPACITY)
    private val _tail = MutableStateFlow<List<LogLine>>(emptyList())

    /** The most recent [CAPACITY] log lines, oldest first. */
    val tail: StateFlow<List<LogLine>> = _tail.asStateFlow()

    fun append(line: LogLine) {
        val snapshot = synchronized(lock) {
            lines.addLast(line)
            while (lines.size > CAPACITY) lines.removeFirst()
            lines.toList()
        }
        _tail.value = snapshot
    }

    fun clear() {
        synchronized(lock) { lines.clear() }
        _tail.value = emptyList()
    }
}
